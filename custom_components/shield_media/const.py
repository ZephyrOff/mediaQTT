"""Constants for the Shield Media integration."""

DOMAIN = "shield_media"

CONF_TOKEN = "token"
DEFAULT_PORT = 8765
WS_PATH = "/ws"

# Android package name -> display name
APP_NAMES = {
    "com.google.android.youtube.tv": "YouTube",
    "com.netflix.ninja": "Netflix",
    "com.amazon.amazonvideo.livingroom": "Prime Video",
    "com.canal.android.canal": "Canal+",
    "com.disney.disneyplus": "Disney+",
    "com.apple.atve.android.appletv": "Apple TV",
    "org.videolan.vlc": "VLC",
}
