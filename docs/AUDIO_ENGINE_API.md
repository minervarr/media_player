# Matrix Audio Engine -- API Reference

Standalone Android library (AAR) providing high-quality audio playback with gapless transitions, parametric EQ, USB DAC output, and DSD support. No third-party dependencies.

## Quick Start

### Gradle dependency

```groovy
// settings.gradle
include ':app', ':audioengine'

// app/build.gradle
dependencies {
    implementation project(':audioengine')
}
```

### 3-line playback

```java
MatrixPlayer player = new MatrixPlayer();
player.play(context, Uri.parse("content://media/external/audio/12345"));
// ...
player.release();
```

### Lifecycle

Create a `MatrixPlayer` once (e.g. in a Service or retained component). Call `release()` when done -- this frees native buffers, threads, and the audio output device.

---

## MatrixPlayer API

`MatrixPlayer` is a facade over `AudioEngine` that handles output creation, EQ lifecycle, and listener wrapping automatically.

### Playback

| Method | Description |
|--------|-------------|
| `play(Context, Uri)` | Play a local file or content URI |
| `playStream(MediaDataSource, long durationHintUs)` | Play from a streaming data source |
| `pause()` | Pause playback |
| `resume()` | Resume playback |
| `togglePlayPause()` | Toggle between play and pause |
| `seekTo(int positionMs)` | Seek to position in milliseconds |
| `stop()` | Stop playback and release current track resources |
| `release()` | Release all resources (player cannot be reused) |

### Gapless Playback

| Method | Description |
|--------|-------------|
| `queueNext(Context, Uri)` | Pre-decode next track for gapless transition |
| `queueNextStream(MediaDataSource, long)` | Pre-decode next track from stream |
| `cancelNext()` | Cancel queued next track |

Queue the next track while the current one is playing. When the current track ends, playback transitions seamlessly with no gap. The `OnTrackTransitionListener` fires at the transition point.

```java
player.setOnTrackTransitionListener(p -> {
    // Current track just ended, next track is now playing.
    // Queue the next-next track here for continuous gapless playback.
    player.queueNext(context, nextNextUri);
});
player.play(context, firstUri);
player.queueNext(context, secondUri);
```

### Audio Output

| Method | Description |
|--------|-------------|
| `switchOutput(AudioOutput)` | Route audio to a different output device |

The default output is `AudioTrackOutput` (device speaker/headphone jack). Switch to `UsbAudioOutput` for USB DAC playback.

```java
// Switch to USB DAC (requires USB file descriptor from UsbDeviceConnection)
UsbAudioOutput usbOut = new UsbAudioOutput(fd);
usbOut.open();
player.switchOutput(usbOut);

// Switch back to speaker
player.switchOutput(new AudioTrackOutput());
```

#### Custom AudioOutput

Implement the `AudioOutput` interface:

```java
public interface AudioOutput {
    boolean configure(int sampleRate, int channelCount, int encoding, int sourceBitDepth);
    boolean start();
    int write(byte[] data, int offset, int length);
    void pause();
    void resume();
    void flush();
    void stop();
    void release();
}
```

### Parametric EQ

| Method | Description |
|--------|-------------|
| `setEqProfile(EqProfile)` | Apply an EQ profile (null to disable) |
| `loadEqProfiles(Context)` | Static -- load built-in EQ profiles from asset |

Coefficients are computed automatically using the Bristow-Johnson Audio EQ Cookbook when the sample rate becomes known (after prepare or track transition).

```java
List<EqProfile> profiles = MatrixPlayer.loadEqProfiles(context);
player.setEqProfile(profiles.get(0));  // apply first profile
player.setEqProfile(null);             // disable EQ
```

#### Filter Types

| Type | Description |
|------|-------------|
| `PK` | Peaking EQ -- boost/cut at center frequency |
| `LSC` | Low shelf -- boost/cut below corner frequency |
| `HSC` | High shelf -- boost/cut above corner frequency |

Each `EqProfile` contains a preamp (dB) and a list of `Filter` objects with `type`, `fc` (Hz), `gain` (dB), and `q` (quality factor).

#### Creating Custom Profiles

```java
EqProfile custom = new EqProfile();
custom.name = "My Profile";
custom.preamp = -3.0;
custom.filters = new ArrayList<>();

EqProfile.Filter bass = new EqProfile.Filter();
bass.type = "LSC";
bass.fc = 105;
bass.gain = 4.0;
bass.q = 0.71;
custom.filters.add(bass);

player.setEqProfile(custom);
```

### DSD Support

The engine natively parses DSF (Sony) and DFF (Philips DSDIFF) files. Over USB, DSD data is sent as raw bitstream when the DAC supports native DSD. For speaker output, DSD is not supported (returns an error).

Supported formats:
- DSD64 (2.8 MHz)
- DSD128 (5.6 MHz)
- DSD256 (11.2 MHz)

### Signal Path Info

Query the current decode/output chain for UI display:

```java
SignalPathInfo info = player.getSignalPathInfo();
// info.sourceFormat, info.sourceRate, info.sourceBitDepth
// info.codecName, info.decodedEncoding
// info.outputDevice, info.outputRate, info.outputBitDepth
// info.eqActive, info.eqProfileName
```

`SignalPathInfo` includes quality color helpers (`getSourceQualityColor()`, `getDecodeQualityColor()`, `getOutputQualityColor()`) returning ARGB ints for signal path visualization.

### State Queries

| Method | Returns |
|--------|---------|
| `isPlaying()` | Whether audio is actively playing |
| `getCurrentPosition()` | Playback position in ms |
| `getDuration()` | Track duration in ms |
| `getSampleRate()` | Sample rate in Hz |
| `getChannelCount()` | Number of channels |
| `getEncoding()` | `AudioFormat.ENCODING_PCM_*` constant |
| `getSourceBitDepth()` | Source file bit depth |
| `isDsd()` | Whether current track is DSD |
| `getMime()` | MIME type of source |
| `getCodecName()` | MediaCodec decoder name |

### Listeners

```java
player.setOnPreparedListener(p -> { /* track ready to play */ });
player.setOnCompletionListener(p -> { /* track finished */ });
player.setOnErrorListener((p, msg) -> { /* error occurred */ });
player.setOnTrackTransitionListener(p -> { /* gapless transition */ });
```

---

## Advanced: Direct AudioEngine

For full control, access the engine directly:

```java
AudioEngine engine = player.getEngine();
// or create standalone:
AudioEngine engine = new AudioEngine();
engine.switchOutput(new AudioTrackOutput());
```

### Thread Model

AudioEngine uses three threads:
1. **Decode thread** -- reads from MediaExtractor/MediaCodec, writes PCM to ring buffer
2. **Output thread** -- reads from ring buffer, applies EQ, writes to AudioOutput
3. **Seek executor** -- handles async seek operations

Listener callbacks fire on the decode or output thread, not the main thread. Post to a Handler if you need to update UI.

### Listener Threading

```java
Handler mainHandler = new Handler(Looper.getMainLooper());
engine.setOnPreparedListener(e -> mainHandler.post(() -> {
    // Safe to update UI here
}));
```

---

## Requirements

- **minSdk**: 24 (Android 7.0)
- **Native**: NDK with CMake 3.22.1, C++17
- **Dependencies**: None (pure Android SDK)
- **Outputs**: `libmatrix_audio.so` (native), `eq_profiles.bin` (asset)
