# Android IP Camera (Fork with Night Vision & Manual Controls)

**English** | [中文](README_zh.md)

This is a simple fork of the original [android-ip-camera](https://github.com/DigitallyRefined/android-ip-camera). I modified it mainly to add some features for my personal needs, such as better night vision and more manual control over the camera hardware. It's especially useful for turning old phones (like a Samsung S10) into basic low-light monitoring cameras.

***

## Added Features

### Better Night Vision

I've added a few algorithms to help see things in the dark:

- **Pixel Binning**: Does a 2x2 spatial average on raw data to reduce noise when the ISO is high.
- **Frame Averaging**: Stacks several frames in the browser to create a "long exposure" effect for static scenes.
- **Contrast & Brightness (CLAHE & Gamma)**: Helps pull details out of the shadows without making the whole image too bright.
- **False Color**: Maps brightness levels to different colors to help spot movement in very dark areas.
- **Sharpening**: A simple filter to make edges look a bit clearer.

### Manual Hardware Control

Added some options to let you control the camera manually:

- **Manual ISO & Shutter**: You can set these yourself instead of letting the camera decide, which is great for dark rooms.
- **Manual Focus**: A slider to fix the focus distance so it doesn't "hunt" for focus in the dark.
- **Lens Settings**: Remembers different settings for each lens (Ultra-wide, Main, etc.).
- **Flash Intensity**: If your device supports it (API 33+), you can adjust how bright the flash is.

### "Gecko Mode" (Motion Detection)

A simple motion detection mode that compares the current frame with one from a few seconds ago. It's designed to catch very slow movements that normal motion detection might miss.

### Web Dashboard Tweaks

- **4K Support**: Works better with high-resolution streams.
- **Network Indicator**: A simple indicator to show how much bandwidth you're using.
- **Zoom Minimap**: A small map to help you see where you're looking when zoomed in.Had some bug but lazy to fix.
- **Snapshot Gallery**: A place to view and manage photos you've taken remotely.

***

## Getting Started

1. **Original Setup**: For the basics like how to install or generate HTTPS certificates, please check the [original project](https://github.com/DigitallyRefined/android-ip-camera).
2. **Usage**: Open the web interface and you'll see a "Night Enhancement" section where you can turn these features on or off.

## Notes

Running the camera 24/7 with these enhancements can make the phone get quite warm.

- It's a good idea to use the **"Battery Protect" (80% limit)** setting on your phone if it has it.
- Using **Pixel Binning** can help keep things running smoother.

