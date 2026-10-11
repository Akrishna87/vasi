# Vaasi: PDFs read aloud

Vaasi (வாசி, "read!") is an Android app that turns a PDF into natural, human-sounding speech.
The voices are **Kokoro**, an open neural text-to-speech model, running on the phone itself
through [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx). After a one-time voice
download nothing goes to the internet, and nothing you listen to leaves the phone.

## What it does

- **Add a PDF** with the Add PDF button, or open or share one to Vaasi from Files, Gmail, a
  browser or any other app.
- **Cleans up the text first**: drops running headers, footers and page numbers, mends
  words hyphenated across lines, rejoins paragraphs split by a page break, fixes ligatures
  (ﬁ, ﬂ) and skips citation marks like [12].
- **Starts where the book starts**: skips the title page, copyright notice and table of
  contents and begins at the Introduction, Prologue or Chapter 1 (using the PDF's own
  bookmarks when it has them). **Contents** lists the chapters to jump to, and the very
  beginning of the PDF is always there too.
- **Reads it aloud** sentence by sentence, highlighting the sentence being spoken. Tap any
  sentence to jump there; drag the slider to move through the book.
- **Different voices**: 11 voices in the Compact pack and 28 in the Studio pack, American
  and British, women and men. Tap ▶ next to a voice to hear it before choosing.
- **Dialogue voice**: optionally reads words in "quotation marks" in a second voice, so a
  story sounds like a narrator and a speaker.
- **Keeps going in the background** with the screen off, with play/pause and skip on the
  lock screen, in the notification and on headphones. It pauses when headphones are
  unplugged or a call comes in.
- **Remembers where you stopped** in every PDF.
- **Converts the whole PDF to audio**: as soon as a PDF is added, Vaasi turns it into audio
  in the background (screen off is fine), working just ahead of wherever you're listening, so
  playback never has to wait for the voice. You can start listening straight away. It saves
  after every few sentences, so an interrupted conversion carries on where it stopped. The
  audio takes about 22 MB per hour (AAC, 48 kbps) and can be deleted from the ⋮ menu.
- **Save to Music**: joins the converted audio into one `.m4a` in `Music/Vaasi`, to play in
  any music app or copy elsewhere (seconds, once converted).
- Speed from 0.6× to 1.8×. The speed changes how fast the voice speaks, not the pitch.

## Voice packs

| Pack    | Download | Voices | Speed* | Notes |
|---------|----------|--------|--------|-------|
| **Studio** (recommended) | ~330 MB | 28 | 0.45–0.5 | Kokoro v1.0. The most natural voices (Heart, Bella, Emma…) and the quickest to speak. |
| Compact | ~99 MB | 11 | 0.7–1.4 | Kokoro v0.19, int8. Smaller, but its int8 maths is slow on phone CPUs, so many phones pause between sentences. |

\* Seconds of work per second of speech with 4 threads, measured on GitHub's ARM (Neoverse-N2)
and x86 runners by `bench/` on the `claude/voice-bench` branch. Under 1 means the voice keeps
ahead of playback; phone cores are usually somewhat slower than these.

Both are downloaded from sherpa-onnx's GitHub releases by Android's download manager, so a
download carries on if you leave the app. The voice is loaded as soon as a book is opened, and
the first clause of the opening sentence is spoken on its own so listening starts quickly.

## Expressive reading

Kokoro has no emotion control, so Vaasi reads the mood of each sentence (from its words,
"!" and capitals, and dialogue tags such as *she whispered* or *he shouted*) and delivers it
through pace, pitch, loudness and pauses: a little faster and brighter when happy or excited,
slower and lower when sad, quieter for a whisper, louder for a shout. Speech in quotation marks
is acted fully; narration is only lightly coloured. It's on by default and can be turned off in
Voices. The logic is in `tts/Expression.kt`.

## Install

Every push builds the app on GitHub Actions, tests it on an Android emulator and publishes
it as the [**vaasi-latest** release](../../releases/tag/vaasi-latest). On your phone, download
**Vaasi.apk** from there, open it, and allow installing from your browser when asked. New
builds install as updates over the old one. The release also has **Vaasi-sample.m4a**, a
test PDF read aloud by the emulator, so you can hear the voice before installing.

Needs Android 10 or newer.

## Not yet

- **Other languages.** Kokoro v1.0 (the Studio pack) already has Hindi, Spanish, French,
  Italian, Portuguese, Japanese and Chinese voices; they need their own text handling.
  Tamil needs a different model.
- **Scanned PDFs** (photos of pages) have no text to read. They would need OCR first.

## How it fits together

```
PDF ──PdfBox──▶ page text ──TextCleaner──▶ paragraphs ──Sentences──▶ Book (saved as JSON)
                                                                         │
             ReaderService: Kokoro speaks a few sentences ahead ◀────────┘
             ──▶ AudioTrack, with the highlight following the playback position
```

- `text/`: PDF extraction (`PdfText`), clean-up (`TextCleaner`), sentence splitting and
  quote detection (`Sentences`), chapters and where to start (`Contents`), and the `Book` model. Everything except `PdfText` is plain
  Kotlin with unit tests.
- `tts/`: the voice catalogue (`Voices.kt`), downloading and unpacking packs (`Packs`,
  `Archive`), and the sherpa-onnx engine (`Tts`).
- `play/`: the foreground `ReaderService` with its media session and notification, plus
  voice previews.
- `convert/`: `ConvertService` turns books into segment files (`Conversion` tracks which
  sentences are in which file, and where), with the AAC encoder and decoder.
- `ui/`: the Compose screens.

## Building

```
./gradlew assembleRelease
```

The first build downloads the sherpa-onnx AAR (about 48 MB) into `app/libs/`. Unit tests:
`./gradlew testReleaseUnitTest`. The emulator test is `ci/smoke-test.sh`, run by
`.github/workflows/vaasi-apk.yml` with a PDF made by `ci/make_test_pdf.py`.
