import re

with open('/storage/emulated/0/AndroidIDEProjects/MusicHapticsX/app/src/main/res/values/themes.xml', 'r') as f:
    content = f.read()

content = content.replace('<item name="android:windowBackground">#F2F2F7</item>', '<item name="android:windowBackground">@android:color/transparent</item>\n        <item name="android:colorBackgroundCacheHint">#F2F2F7</item>\n        <item name="android:windowIsTranslucent">true</item>')

with open('/storage/emulated/0/AndroidIDEProjects/MusicHapticsX/app/src/main/res/values/themes.xml', 'w') as f:
    f.write(content)

with open('/storage/emulated/0/AndroidIDEProjects/MusicHapticsX/app/src/main/res/values-night/themes.xml', 'r') as f:
    content_night = f.read()

content_night = content_night.replace('<item name="android:windowBackground">#FFFFFF</item>', '<item name="android:windowBackground">@android:color/transparent</item>\n        <item name="android:colorBackgroundCacheHint">#FFFFFF</item>\n        <item name="android:windowIsTranslucent">true</item>')

with open('/storage/emulated/0/AndroidIDEProjects/MusicHapticsX/app/src/main/res/values-night/themes.xml', 'w') as f:
    f.write(content_night)
