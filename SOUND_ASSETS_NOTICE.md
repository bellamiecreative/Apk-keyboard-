# Keyboard sound assets — provenance and licensing notice

`app/src/main/res/raw/snd_*.wav` are short clips cut from the project owner's own screen recordings of an iPhone keyboard.
They are **private reference assets. They are NOT licensed or cleared for redistribution.** The keyboard sounds heard on an
iPhone are Apple's; resolve the licensing question (or replace these files with sounds you own the rights to) **before any
public release, store listing or public repository publication.**

The clips are bit-exact slices: no sample value was changed, only the silence around each sound was removed so playback
starts instantly. The original recordings are not included in this project and were not modified.
Format of every clip: 48 kHz, mono, 16-bit PCM WAV. Source audio was lossy (AAC) video audio, so quality is limited by that.

| Resource (res/raw) | Source recording | Event in source | Used for |
|---|---|---|---|
| snd_letter.wav | 01_Keys_Letters_Numbers_Symbols_Emoji_Both_Languages.wav | 1st (only) | Letters, numbers, symbols, emoji taps (both languages) |
| snd_caps_lock.wav | 02_Shift_Double_Tap_Caps_Lock.wav | 2nd click only | Second Shift tap (Caps Lock) |
| snd_shift_on.wav | 03_Shift_Turn_On.wav | 1st (only) | Shift ON; also the fallback for unlisted controls that already gave feedback |
| snd_shift_off.wav | 04_Shift_Turn_Off.wav | 1st (only) | Shift OFF (including leaving Caps Lock) |
| snd_key_123.wav | 05_Number_123_Key.wav | 1st (only) | 123 / ABC layout key |
| snd_emoji_key.wav | 06_Emoji_Key.wav | 1st (only) | Emoji key |
| snd_space.wav | 07_Spacebar.wav | 1st (only) | Space bar, after a confirmed tap only |
| snd_return.wav | 08_Return_Enter.wav | 1st (only) | Return / Enter |
| snd_backspace_tap.wav | 09_Backspace_Single_Tap.wav | 1st (only) | Backspace press |
| snd_backspace_hold.wav | 10_Backspace_Hold_Repeat.wav | 9th event (a repeat tick) | Backspace auto-repeat ticks |

Exact sample ranges are in the table in the build report. Globe and Voice play no sound.
