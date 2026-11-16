#!/usr/bin/env python3
"""Reference MDX-Net (UVR_MDXNET_9482) separation pipeline.

Replicates the algorithm of sherpa-onnx's OfflineSourceSeparationUvrImpl
(Apache-2.0) with numpy + onnxruntime:
  - outer chunks of num_chunks*sr samples with `margin` overlap on both sides
  - per chunk: zero-trim pad, split into segments of hop*(dim_t-1) samples,
    STFT (Hann, center reflect-pad), model inference on [1,4,dim_f,dim_t],
    iSTFT, trim, stitch, drop margins
  - model output = vocals spectrogram; accompaniment = mix - vocals (time domain)
Model metadata (embedded in the ONNX file): sr=44100, n_fft=4096, hop=1024,
win=4096 hann, dim_f=2048, dim_t=256.
"""
import argparse, sys, time
import numpy as np
import onnxruntime as ort
import soundfile as sf

SR = 44100
N_FFT = 4096
HOP = 1024
DIM_F = 2048
DIM_T = 256
MARGIN = 44100
NUM_CHUNKS = 15

WINDOW = np.hanning(N_FFT + 1)[:-1].astype(np.float32)  # periodic Hann, like torch


def stft(x: np.ndarray) -> np.ndarray:
    """x: (samples,) -> complex (frames, n_fft/2+1), center reflect pad."""
    pad = N_FFT // 2
    xp = np.pad(x, pad, mode="reflect")
    frames = 1 + (len(xp) - N_FFT) // HOP
    idx = np.arange(N_FFT)[None, :] + HOP * np.arange(frames)[:, None]
    spec = np.fft.rfft(xp[idx] * WINDOW[None, :], axis=1)
    return spec.astype(np.complex64)


def istft(spec: np.ndarray, length: int) -> np.ndarray:
    """spec: complex (frames, bins) -> (length,) samples, inverse of stft()."""
    frames = spec.shape[0]
    pad = N_FFT // 2
    total = N_FFT + HOP * (frames - 1)  # padded-domain length
    out = np.zeros(total, dtype=np.float32)
    env = np.zeros(total, dtype=np.float32)
    frames_t = np.fft.irfft(spec, n=N_FFT, axis=1).astype(np.float32) * WINDOW[None, :]
    for f in range(frames):
        s = f * HOP
        out[s:s + N_FFT] += frames_t[f]
        env[s:s + N_FFT] += (WINDOW * WINDOW)
    np.divide(out, env, out=out, where=env > 1e-8)
    return out[pad:pad + length]


class Separator:
    def __init__(self, model_path: str, threads: int = 2):
        so = ort.SessionOptions()
        so.intra_op_num_threads = threads
        so.inter_op_num_threads = 1
        self.sess = ort.InferenceSession(model_path, sess_options=so, providers=["CPUExecutionProvider"])
        self.in_name = self.sess.get_inputs()[0].name

    def run_segment(self, ch0: np.ndarray, ch1: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        """One segment of exactly hop*(dim_t-1) samples per channel -> vocals."""
        s0 = stft(ch0)
        s1 = stft(ch1)
        assert s0.shape[0] == DIM_T, s0.shape
        x = np.empty((1, 4, DIM_F, DIM_T), dtype=np.float32)
        x[0, 0] = s0.T.real[:DIM_F]
        x[0, 1] = s0.T.imag[:DIM_F]
        x[0, 2] = s1.T.real[:DIM_F]
        x[0, 3] = s1.T.imag[:DIM_F]
        y = self.sess.run(None, {self.in_name: x})[0]
        o0 = np.zeros((DIM_T, N_FFT // 2 + 1), dtype=np.complex64)
        o1 = np.zeros((DIM_T, N_FFT // 2 + 1), dtype=np.complex64)
        o0.T[:DIM_F] = y[0, 0] + 1j * y[0, 1]
        o1.T[:DIM_F] = y[0, 2] + 1j * y[0, 3]
        seg_len = HOP * (DIM_T - 1)
        return istft(o0, seg_len), istft(o1, seg_len)

    def process_chunk(self, ch0, ch1, is_first, is_last, progress=[0, 0]):
        trim = N_FFT // 2
        chunk_size = HOP * (DIM_T - 1)  # 261120
        gen_size = chunk_size - 2 * trim  # 257024
        n = len(ch0)
        pad = gen_size - (n % gen_size)
        padded0 = np.concatenate([np.zeros(trim, np.float32), ch0, np.zeros(pad + trim, np.float32)])
        padded1 = np.concatenate([np.zeros(trim, np.float32), ch1, np.zeros(pad + trim, np.float32)])
        out0, out1 = [], []
        for i in range(0, n + pad, gen_size):
            v0, v1 = self.run_segment(padded0[i:i + chunk_size], padded1[i:i + chunk_size])
            out0.append(v0[trim:len(v0) - trim])
            out1.append(v1[trim:len(v1) - trim])
            progress[0] += 1
            print(f"  segment {progress[0]}/{progress[1]}", flush=True)
        a0 = np.concatenate(out0)
        a1 = np.concatenate(out1)
        start = 0 if is_first else MARGIN
        end = (len(a0) - pad) if is_last else (len(a0) - pad - MARGIN)
        return a0[start:end], a1[start:end]

    def separate(self, mix: np.ndarray) -> np.ndarray:
        """mix: (2, samples) float32 -> accompaniment (2, samples)."""
        ch0, ch1 = mix[0], mix[1]
        total = len(ch0)
        chunk_size = min(NUM_CHUNKS * SR, total)
        margin = min(MARGIN, chunk_size)
        # count segments for progress
        starts = list(range(0, total, chunk_size))
        total_segments = 0
        chunks = []
        for s in starts:
            a = max(0, s - margin)
            b = min(s + chunk_size + margin, total)
            if a >= b:
                break
            chunks.append((a, b))
            seg_n = (b - a)
            g = (HOP * (DIM_T - 1) - N_FFT)
            pad = g - (seg_n % g)
            total_segments += (seg_n + pad) // g
            if b == total:
                break
        progress = [0, total_segments]
        voc0, voc1 = [], []
        for idx, (a, b) in enumerate(chunks):
            v0, v1 = self.process_chunk(ch0[a:b], ch1[a:b], idx == 0, idx == len(chunks) - 1, progress)
            voc0.append(v0)
            voc1.append(v1)
        vocals0 = np.concatenate(voc0)[:total]
        vocals1 = np.concatenate(voc1)[:total]
        acc = np.stack([ch0[:total] - vocals0, ch1[:total] - vocals1])
        return acc


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("input")
    ap.add_argument("output")
    ap.add_argument("--model", default="UVR_MDXNET_9482.onnx")
    ap.add_argument("--seconds", type=float, default=0, help="only process first N seconds")
    ap.add_argument("--threads", type=int, default=2)
    args = ap.parse_args()

    data, sr = sf.read(args.input, dtype="float32", always_2d=True)
    assert sr == SR, sr
    mix = np.ascontiguousarray(data.T)  # (2, samples)
    if args.seconds > 0:
        mix = mix[:, : int(args.seconds * SR)]
    in_samples = mix.shape[1]
    print(f"input: {in_samples} samples = {in_samples / SR:.3f}s, sr={sr}")
    sep = Separator(args.model, threads=args.threads)
    t0 = time.time()
    acc = sep.separate(mix)
    elapsed = time.time() - t0
    assert acc.shape[1] == in_samples, (acc.shape, in_samples)
    sf.write(args.output, acc.T, SR, subtype="PCM_16")
    print(f"output: {args.output} samples={acc.shape[1]} duration={acc.shape[1] / SR:.3f}s")
    print(f"elapsed={elapsed:.1f}s  RTF={elapsed / (in_samples / SR):.3f}")


if __name__ == "__main__":
    main()
