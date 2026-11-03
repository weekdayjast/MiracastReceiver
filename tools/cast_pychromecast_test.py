"""mDNS and third-party sender test. Requires pychromecast and an ADB reverse on 18089.

python tools/cast_pychromecast_test.py "Living Room TV" --video-file sample.mp4
"""
import argparse
import time
import pychromecast
from zeroconf import Zeroconf, IPVersion
from cast_smoke_test import audio_server


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("name", help="Exact friendly name advertised by the receiver")
    parser.add_argument("--video-file")
    args = parser.parse_args()
    server = audio_server(args.video_file)
    cast = None
    browser = None
    discovery = Zeroconf(ip_version=IPVersion.V4Only)
    try:
        casts, browser = pychromecast.get_listed_chromecasts(
            friendly_names=[args.name], discovery_timeout=20, timeout=5, tries=1,
            zeroconf_instance=discovery)
        assert casts, "Receiver not found through mDNS"
        cast = casts[0]
        cast.wait(timeout=10)
        print(f"PASS mDNS discovery and pychromecast connection: {cast.name} @ {cast.cast_info.host}", flush=True)
        controller = cast.media_controller
        controller.play_media("http://127.0.0.1:18089/" + ("video.mp4" if args.video_file else "tone.wav"),
                              "video/mp4" if args.video_file else "audio/wav",
                              title="Cast sender compatibility test", stream_type="BUFFERED")
        controller.block_until_active(timeout=10)

        def wait_for(predicate):
            deadline = time.monotonic() + 20
            while time.monotonic() < deadline:
                controller.update_status()
                if predicate(controller.status):
                    return
                time.sleep(0.3)
            raise AssertionError(f"Unexpected media status: {controller.status}")

        wait_for(lambda s: s.player_state == "PLAYING" and s.current_time > 1)
        print(f"PASS {'video' if args.video_file else 'audio'} LOAD and playback: {controller.status}", flush=True)
        controller.pause()
        wait_for(lambda s: s.player_state == "PAUSED")
        controller.seek(10)
        wait_for(lambda s: s.current_time >= 9.5)
        controller.play()
        wait_for(lambda s: s.player_state == "PLAYING")
        cast.set_volume(0.3)
        controller.stop()
        wait_for(lambda s: s.player_state == "IDLE")
        print("PASS pychromecast PAUSE, SEEK, PLAY, volume and STOP", flush=True)
    finally:
        if cast:
            try:
                cast.quit_app()
            finally:
                cast.disconnect(timeout=5)
        if browser:
            browser.stop_discovery()
        discovery.close()
        server.shutdown()


if __name__ == "__main__":
    main()
