"""Media player entity for the Shield."""

from __future__ import annotations

from datetime import datetime
from typing import Any

from homeassistant.components.media_player import (
    MediaPlayerEntity,
    MediaPlayerEntityFeature,
    MediaPlayerState,
)
from homeassistant.const import CONF_HOST, CONF_PORT
from homeassistant.core import HomeAssistant, callback
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.entity_platform import AddConfigEntryEntitiesCallback
from homeassistant.util import dt as dt_util

from . import ShieldConfigEntry
from .client import ShieldClient, ShieldCommandError, ShieldConnectionError
from .const import APP_NAMES, DOMAIN

FEATURES = {
    "play": MediaPlayerEntityFeature.PLAY,
    "pause": MediaPlayerEntityFeature.PAUSE,
    "stop": MediaPlayerEntityFeature.STOP,
    "seek": MediaPlayerEntityFeature.SEEK,
    "next": MediaPlayerEntityFeature.NEXT_TRACK,
    "previous": MediaPlayerEntityFeature.PREVIOUS_TRACK,
}
STATES = {
    "playing": MediaPlayerState.PLAYING,
    "paused": MediaPlayerState.PAUSED,
    "idle": MediaPlayerState.IDLE,
}


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ShieldConfigEntry,
    async_add_entities: AddConfigEntryEntitiesCallback,
) -> None:
    """Set up the media player."""
    async_add_entities([ShieldMediaPlayer(entry)])


class ShieldMediaPlayer(MediaPlayerEntity):
    """Media player fed by the push state of the app."""

    _attr_has_entity_name = True
    _attr_name = None
    _attr_should_poll = False

    def __init__(self, entry: ShieldConfigEntry) -> None:
        self._client: ShieldClient = entry.runtime_data
        unique_id = entry.unique_id or entry.entry_id
        self._attr_unique_id = unique_id
        self._attr_device_info = DeviceInfo(
            identifiers={(DOMAIN, unique_id)},
            name=f"Shield ({entry.data[CONF_HOST]}:{entry.data[CONF_PORT]})",
            manufacturer="NVIDIA",
            model="Shield",
        )

    async def async_added_to_hass(self) -> None:
        """Subscribe to client updates."""
        self.async_on_remove(self._client.add_listener(self._handle_update))

    @callback
    def _handle_update(self) -> None:
        self.async_write_ha_state()

    @property
    def _data(self) -> dict[str, Any]:
        return self._client.state or {}

    @property
    def available(self) -> bool:
        """Unavailable while the Shield is asleep or unreachable."""
        return self._client.available

    @property
    def state(self) -> MediaPlayerState | None:
        return STATES.get(self._data.get("state"))

    @property
    def supported_features(self) -> MediaPlayerEntityFeature:
        features = MediaPlayerEntityFeature(0)
        for action in self._data.get("supported_actions", []):
            features |= FEATURES.get(action, MediaPlayerEntityFeature(0))
        return features

    @property
    def media_title(self) -> str | None:
        return self._data.get("title")

    @property
    def media_duration(self) -> int | None:
        duration = self._data.get("duration_ms")
        return None if duration is None else duration // 1000

    @property
    def media_position(self) -> int | None:
        position = self._data.get("position_ms")
        return None if position is None else position // 1000

    @property
    def media_position_updated_at(self) -> datetime | None:
        value = self._data.get("position_updated_at")
        return dt_util.parse_datetime(value) if value else None

    @property
    def app_id(self) -> str | None:
        return self._data.get("app_id")

    @property
    def app_name(self) -> str | None:
        app_id = self.app_id
        return APP_NAMES.get(app_id, app_id) if app_id else None

    async def _send(self, command: str, **kwargs: Any) -> None:
        try:
            await self._client.async_command(command, **kwargs)
        except ShieldConnectionError as err:
            raise HomeAssistantError("The Shield is unreachable") from err
        except ShieldCommandError as err:
            raise HomeAssistantError(f"The Shield refused '{command}': {err}") from err

    async def async_media_play(self) -> None:
        await self._send("play")

    async def async_media_pause(self) -> None:
        await self._send("pause")

    async def async_media_stop(self) -> None:
        await self._send("stop")

    async def async_media_next_track(self) -> None:
        await self._send("next")

    async def async_media_previous_track(self) -> None:
        await self._send("previous")

    async def async_media_seek(self, position: float) -> None:
        """Seek to a position given in seconds."""
        await self._send("seek", position_ms=int(position * 1000))
