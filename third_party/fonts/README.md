# Bundled font

`app/src/main/res/font/jetbrainsmono_regular.ttf` and
`jetbrainsmono_bold.ttf` are the unmodified **JetBrains Mono** 2.304 release
fonts (`fonts/ttf/JetBrainsMono-Regular.ttf` and `-Bold.ttf`), copied byte for
byte from the official release:

    https://github.com/JetBrains/JetBrainsMono/releases/tag/v2.304

They are licensed under the SIL Open Font License 1.1; the licence text that
ships with the release is in `OFL.txt` next to this file. No other fonts or
icon sets are bundled.

SHA-256 of the files, so a reviewer can compare them with the release:

    a0bf60ef0f83c5ed4d7a75d45838548b1f6873372dfac88f71804491898d138f  jetbrainsmono_regular.ttf
    5590990c82e097397517f275f430af4546e1c45cff408bde4255dad142479dcb  jetbrainsmono_bold.ttf

(Android resource names must be lower case, which is why the files are renamed
from `JetBrainsMono-Regular.ttf` and `JetBrainsMono-Bold.ttf`.)
