"""Times the Kokoro voice packs the way the app uses them (sherpa-onnx, CPU).

Usage: python3 bench/tts_bench.py <packs dir> <out dir>
Prints load time and real-time factor (seconds of work per second of speech; under 1
means the voice keeps up with playback) for each pack and thread count.
"""
import glob, json, os, sys, time, wave
import numpy as np
import sherpa_onnx

PACKS, OUT = sys.argv[1], sys.argv[2]
os.makedirs(OUT, exist_ok=True)
SHORT = "Chapter One."
TEXT = ("The lighthouse keeper woke before the sun, as he had done every morning for thirty years. "
        "He climbed the narrow stairs, counted the steps out of habit, and opened the shutters to the grey sea.")

def engine(d, model, lexicon, threads):
    kokoro = sherpa_onnx.OfflineTtsKokoroModelConfig(
        model=model, voices=f"{d}/voices.bin", tokens=f"{d}/tokens.txt",
        data_dir=f"{d}/espeak-ng-data", lexicon=lexicon)
    cfg = sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(kokoro=kokoro, num_threads=threads))
    return sherpa_onnx.OfflineTts(cfg)

def save(path, samples, rate):
    pcm = (np.clip(np.array(samples), -1, 1) * 32767).astype(np.int16)
    with wave.open(path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(rate); w.writeframes(pcm.tobytes())

runs = [
    ("compact-int8-v0.19", "compact", None, 1),
    ("studio-fp32-v1.0", "studio", "studio/model.onnx", 3),
    ("studio-int8-v1.0", "studio", "studio/model.int8.onnx", 3),
]
results = []
for name, pack, model, sid in runs:
    d = f"{PACKS}/{pack}"
    model = f"{PACKS}/{model}" if model else min(glob.glob(f"{d}/*.onnx"), key=len)
    if not os.path.exists(model):
        print("skip", name); continue
    lexicon = f"{d}/lexicon-us-en.txt" if os.path.exists(f"{d}/lexicon-us-en.txt") else ""
    for threads in (1, 2, 4):
        t = time.time(); tts = engine(d, model, lexicon, threads); load = time.time() - t
        t = time.time(); a = tts.generate(SHORT, sid=sid, speed=1.0); first = time.time() - t
        times, dur = [], 0
        for _ in range(2):
            t = time.time(); a = tts.generate(TEXT, sid=sid, speed=1.0); times.append(time.time() - t)
            dur = len(a.samples) / a.sample_rate
        rtf = min(times) / dur
        r = dict(pack=name, threads=threads, model_mb=round(os.path.getsize(model) / 1e6), load_s=round(load, 2),
                 short_s=round(first, 2), speech_s=round(dur, 1), rtf=round(rtf, 3))
        results.append(r); print(json.dumps(r), flush=True)
        if threads == 4:
            save(f"{OUT}/{name}.wav", a.samples, a.sample_rate)
        del tts
json.dump(results, open(f"{OUT}/results.json", "w"), indent=1)
