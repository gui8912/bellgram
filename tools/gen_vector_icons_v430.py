#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Generate the small Material-style vector icons used by the v4.3.0 UI.

Third-party provenance (audit dimension 6):
  The 24dp path data in ICONS comes from Google Material Design Icons
  (https://github.com/google/material-design-icons), Apache License 2.0,
  with minor geometric trimming for this project. These paths are the ONLY
  reused third-party material in the app: no third-party Java/Kotlin source
  was copied, and every generated drawable repeats this provenance comment.

Colour is applied through @color/icon_tint on the path (not android:tint on the
<vector> root) so it still works on API 23-25 and switches with values-night.
"""
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "res", "drawable")

ICONS = {
    "ic_bell_24": "M12,22c1.1,0 2,-0.9 2,-2h-4c0,1.1 0.89,2 2,2zM18,16v-5"
                  "c0,-3.07 -1.63,-5.64 -4.5,-6.32V4c0,-0.83 -0.67,-1.5 -1.5,-1.5"
                  "s-1.5,0.67 -1.5,1.5v0.68C7.64,5.36 6,7.92 6,11v5l-2,2v1h16v-1l-2,-2z"
                  "M16,17H8v-6c0,-2.48 1.51,-4.5 4,-4.5s4,2.02 4,4.5v6z",
    "ic_volume_24": "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03"
                    "v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06"
                    "c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06"
                    "c4.01,-0.91 7,-4.49 7,-8.77S18.01,4.14 14,3.23z",
    "ic_palette_24": "M12,3c-4.97,0 -9,4.03 -9,9s4.03,9 9,9c0.83,0 1.5,-0.67"
                     " 1.5,-1.5 0,-0.39 -0.15,-0.74 -0.39,-1.01 -0.23,-0.26"
                     " -0.38,-0.61 -0.38,-0.99 0,-0.83 0.67,-1.5 1.5,-1.5H16"
                     "c2.76,0 5,-2.24 5,-5 0,-4.42 -4.03,-8 -9,-8zM6.5,12"
                     "c-0.83,0 -1.5,-0.67 -1.5,-1.5S5.67,9 6.5,9 8,9.67 8,10.5"
                     " 7.33,12 6.5,12zM9.5,8C8.67,8 8,7.33 8,6.5S8.67,5 9.5,5"
                     "s1.5,0.67 1.5,1.5S10.33,8 9.5,8zM14.5,8c-0.83,0 -1.5,-0.67"
                     " -1.5,-1.5S13.67,5 14.5,5s1.5,0.67 1.5,1.5S15.33,8 14.5,8z"
                     "M18.5,11c-0.83,0 -1.5,-0.67 -1.5,-1.5S17.67,8 18.5,8"
                     "s1.5,0.67 1.5,1.5S19.33,11 18.5,11z",
    "ic_snooze_24": "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22"
                    " 22,17.52 22,12S17.52,2 11.99,2zM12,20c-4.42,0 -8,-3.58"
                    " -8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8zM12.5,7H11v6"
                    "l5.25,3.15 0.75,-1.23 -4.5,-2.67z",
    "ic_repeat_24": "M7,7h10v3l4,-4 -4,-4v3H5v6h2V7zM17,17H7v-3l-4,4 4,4v-3"
                    "h12v-6h-2v4z",
    "ic_vibrate_24": "M0,15h2V9H0v6zM3,17h2V7H3v10zM22,9v6h2V9h-2zM19,17h2V7"
                     "h-2v10zM16.5,3h-9C6.67,3 6,3.67 6,4.5v15c0,0.83 0.67,1.5"
                     " 1.5,1.5h9c0.83,0 1.5,-0.67 1.5,-1.5v-15c0,-0.83"
                     " -0.67,-1.5 -1.5,-1.5zM16,19H8V5h8v14z",
    "ic_moon_24": "M12,3a9,9 0 1,0 9,9c0,-0.46 -0.04,-0.92 -0.1,-1.36"
                  "a5.39,5.39 0 0,1 -4.4,2.26 5.4,5.4 0 0,1 -5.4,-5.4"
                  "c0,-1.28 0.45,-2.45 1.19,-3.37C12.36,3.05 12.18,3 12,3z",
    "ic_tune_24": "M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25z"
                  "M20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34"
                  "a0.9959,0.9959 0 0,0 -1.41,0l-1.83,1.83 3.75,3.75 1.83,-1.83z",
    "ic_shield_24": "M12,1L3,5v6c0,5.55 3.84,10.74 9,12 5.16,-1.26 9,-6.45"
                    " 9,-12V5l-9,-4zM12,11.99h7c-0.53,4.12 -3.28,7.79 -7,8.94"
                    "V12H5V6.3l7,-3.11v8.8z",
}

TEMPLATE = '''<?xml version="1.0" encoding="utf-8"?>
<!-- modified: v4.3.0 新增界面图标；路径数据取自 Google Material Design Icons (Apache-2.0, github.com/google/material-design-icons) 24dp 图标集；颜色跟随明亮/暗色主题 -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="@color/icon_tint"
        android:pathData="%s" />
</vector>
'''


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, path in ICONS.items():
        with open(os.path.join(OUT, name + ".xml"), "w",
                  encoding="utf-8", newline="\n") as f:
            f.write(TEMPLATE % path)
    print("wrote %d vector icons into %s" % (len(ICONS), OUT))


if __name__ == "__main__":
    main()
