# Android IP Camera (Enhanced Version)

[![downloads](https://img.shields.io/github/downloads/DigitallyRefined/android-ip-camera/total.svg)](https://github.com/DigitallyRefined/android-ip-camera/releases)
[![downloads](https://img.shields.io/github/downloads/DigitallyRefined/android-ip-camera/latest/total.svg)](https://github.com/DigitallyRefined/android-ip-camera/releases)

**English** | [中文](README_zh.md)

An Android MJPEG IP Camera app with **Professional Control & Night Enhancement**.

## New & Enhanced Features 🚀

This version (Forked) includes significant upgrades over the original project:

### 🌙 Advanced Night Enhancement
- **Temporal Frame Averaging**: Multi-frame stacking to reduce noise and boost brightness in extreme low light (software-based "Long Exposure").
- **Manual Exposure (Hardware)**: Direct control over **ISO** and **Shutter Speed** via Camera2 API to bypass system auto-exposure limits.
- **Digital Gain Boost**: Software curves to amplify shadow details without overblowing highlights.
- **Monochrome Mode**: Toggle grayscale to eliminate chroma noise in dark environments.

### 🎯 Precision Camera Control
- **Manual Focus**: Slide to lock focus at specific distances (0 = Infinity to macro).
- **🎯 Force Auto Focus**: One-tap hardware AF trigger to reset focus when lost in low light.
- **Lens Selection**: Support for **Ultra Wide**, **Main**, and **Telephoto** lenses (specifically optimized for multi-lens devices like Samsung S10).
- **High-Resolution Snapshots**: Capture full-sensor resolution JPEGs via a dedicated Snapshot gallery.

### 🎛️ Modern Web Interface
- **Device Dashboard**: Real-time monitoring of phone **Battery**, **Temperature**, **Focus Status**, and **Uptime**.
- **Software Zoom & Minimap**: Viewport-based digital zoom with a draggable minimap for precise positioning.
- **Motion Detection**: Client-side analysis with visual debug overlays and customizable audio alerts (multiple tones and volume control).
- **Night Vision Filter**: CSS-based visual simulation for improved readability.

### ⚙️ Stability & Performance
- **GPU Memory Management**: Efficient bitmap recycling to prevent browser crashes during 24/7 streaming.
- **Backpressure Handling**: Intelligent frame-skipping to prevent server overload on slow networks.
- **Independent Rotation**: Separate orientation settings for front and back cameras.

## Install
![Desktop Browser](screenshot.webp)


## Standard Features (Legacy)

- 🌎 **Built-in Server**: Just open the stream in any web browser.
- 📴 **Screen Off**: Stream in the background with the display off.
- 🛂 **Security**: Mandatory username and password protection.
- 🔐 **HTTPS/TLS**: Automatic self-signed certificate generation for secure delivery.

## ⚠️ Warning

If you are planning to run this 24/7, please make sure that your phone does not stay at 100% charge. Doing so may damage the battery and cause it to swell up, which could cause it to explode.

Some models include an option to only charge to 80%, make sure this is enabled where possible.

Note: running at a higher image quality may cause some phones to over heat, which can also damage the battery.

## HTTPS/TLS certificates

To protect the stream and the password from being sent in plain-text over HTTP, a certificate can be used to start the stream over HTTPS.

The app will automatically generate a self-signed certificate on first launch, but if you have your own domain you can use [Let's Encrypt](https://letsencrypt.org) to generate a trusted certificate and skip the self-signed security warning message, by changing the TLS certificate in the settings.

To generate a new self-signed certificate, clear the app settings and restart or clone this repo and run `./scripts/generate-certificate.sh` then use the certificate `personal_certificate.p12` file it generates.
