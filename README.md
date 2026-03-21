# Android IP Camera (Professional Night Enhancement Edition)

**English** | [中文](README_zh.md)

This is a heavily modified fork of the original Android IP Camera, specifically engineered to transform high-end legacy devices (like the **Samsung S10**) into professional-grade **Low-Light Observation Stations**.

While the original project provides a solid foundation for streaming, this version focuses entirely on **extreme night vision capabilities**, **manual hardware precision**, and **intelligent biological monitoring (Gecko Mode)**.

---

## 🌙 The "Night Stalker" Suite: Extreme Low-Light Features

This version implements a multi-stage image enhancement pipeline that rivals dedicated digital night vision hardware:

### 🧬 Hardware-Side Pixel Binning (SNR Boost)
- **What it does**: Performs 2x2 spatial averaging on **raw NV21 data** directly on the phone before JPEG compression.
- **The Result**: Effectively doubles the signal-to-noise ratio (SNR). It "crushes" sensor noise at the source, delivering a remarkably clean image even at extreme ISO levels.

### ⏳ Temporal Frame Averaging (Software Long-Exposure)
- **What it does**: Stacks 1-10 consecutive frames in the browser using a recursive weighted averaging algorithm.
- **The Result**: Produces a "Long Exposure" effect for static scenes (like a pet terrarium), virtually eliminating temporal noise and revealing objects in near-total darkness.

### 🌓 Advanced Tone Mapping & Contrast
- **CLAHE (Local Adaptive Contrast)**: Divides the image into an 8x8 grid to perform localized contrast stretching. This reveals details in the darkest shadows (e.g., inside a hide) without overblowing bright background lights.
- **Gamma Correction**: Non-linear curve adjustment to lift mid-tones and shadows intelligently.
- **Shadow Boost (Digital Gain)**: Non-linear software amplification for extreme cases.

### 🌈 False Color (Heatmap Simulation)
- Maps the 0-255 luminance range to a "Blue -> Green -> Red" color spectrum.
- Ideal for identifying subtle heat signatures or movement patterns in environments where the human eye can no longer distinguish grayscale shades.

### 📐 Edge Sharpening
- 3x3 Convolutional kernel to restore object outlines that become "fuzzy" during long-exposure or high-ISO captures.

---

## 🎯 Professional Hardware Control

Take full manual command of your device's optics:

- **Per-Lens Settings Persistence**: Different settings (ISO, Shutter, Focus, Binning) are automatically saved and restored for each lens (Ultra-Wide, Main, Telephoto).
- **Manual Exposure (ISO & Shutter)**: Direct Camera2 API override. Lock your shutter at 1/10s or boost ISO to 3200+ to maximize photon collection.
- **Manual Focus Distance**: Precise slider control (0 = Infinity to macro) to prevent "focus hunting" in the dark.
- **🎯 Force Auto Focus**: Remote one-tap trigger to force a hardware focus/metering cycle.
- **Flash Brightness (API 33+)**: Fine-grained control over the physical torch intensity.

---

## 🦎 Gecko Mode: Intelligent Motion Detection

Designed for monitoring slow-moving reptiles or low-activity subjects:

- **Slow Motion Mode (Gecko Mode)**: Compares the current frame against a reference frame from up to 5 seconds ago. This captures extremely slow displacements that traditional "next-frame" algorithms miss.
- **Background Persistence**: Uses the **Screen Wake Lock API** and direct data-layer processing to ensure detection and audio alerts continue even when the browser tab is in the background or minimized.
- **Pixel Intensity Tuning**: Adjustable threshold for what constitutes a "change," allowing you to filter out sensor noise while catching a moving gecko.

---

## 🛜 Modern Remote Dashboard

- **4K Ultra HD Support**: Hardware-detected 4K streaming with intelligent network backpressure management.
- **Network Pressure Indicator**: Real-time Mbps monitoring with a status dot (Green/Yellow/Red) to help you choose the best resolution.
- **Draggable Minimap**: Precise viewport positioning for digital zoom levels up to 8x.
- **High-Res Snapshot Gallery**: Trigger full-sensor resolution captures remotely and manage them in a collapsible side-panel gallery.

---

## Getting Started

1.  **Original Setup**: For basic installation, HTTPS certificate generation, and legacy features, please refer to the [Original Repository](https://github.com/DigitallyRefined/android-ip-camera).
2.  **Usage**: Open the web dashboard, expand the **Night Enhancement** panel, and begin stacking the algorithms to suit your environment.

---

## ⚠️ Stability & Safety Note

Running 24/7 at high resolutions with multiple enhancement algorithms active can generate significant heat. 
- Always enable **Battery Protect (80% limit)** on your Android device.
- Use **Pixel Binning** to reduce resolution/bandwidth while maintaining high signal quality.
