"""Linux CI kernel semantics + production source contracts; not an Android device test."""
import os
from pathlib import Path
import select
import socket
import tempfile
import threading
import unittest


class ListenerContractTest(unittest.TestCase):
    def test_production_ownership_contract(self):
        root = Path(__file__).resolve().parents[4]
        bridge = (root / "app/src/main/java/cn/classfun/droidvm/daemon/vm/backend/NativeDisplayInputBridge.java").read_text()
        self.assertIn("synchronized boolean startListening(", bridge)
        self.assertIn("synchronized void release()", bridge)
        self.assertNotIn("inputClosed", bridge)
        self.assertNotIn("nativeCloseFd(slot.serverFd)", bridge)
        self.assertIn("flags | OsConstants.O_NONBLOCK", bridge)
        self.assertIn("UnixHelper.nativePollIn2(", bridge)
        self.assertIn("slot.owner.stop(UnixHelper::nativeCloseFd)", bridge)
        self.assertIn("slot.owner.finish(UnixHelper::nativeCloseFd)", bridge)
        self.assertLess(bridge.index("if (built.containsKey("), bridge.index("UnixHelper.nativeUnixListen(path)"))
        worker = bridge.split("private void startInputAcceptThread", 1)[1].split("private void startStatusReadThread", 1)[0]
        self.assertNotIn("deleteFile(", worker)
        self.assertNotIn("Thread.sleep", worker)
        release = bridge.split("synchronized void release()", 1)[1]
        self.assertNotIn("owner.finish", release)
        self.assertLess(release.index("slots = null"), release.index("closeSlots("))

    def test_real_pipe_hup_wakes_poll_before_and_during_wait(self):
        for stop_before_wait in (False, True):
            with self.subTest(stop_before_wait=stop_before_wait), tempfile.TemporaryDirectory() as tmp:
                with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as listener:
                    listener.bind(str(Path(tmp) / "input.sock"))
                    listener.listen(1)
                    listener.setblocking(False)
                    read_fd, write_fd = os.pipe()
                    entered = threading.Event()
                    result = []
                    errors = []
                    if stop_before_wait:
                        os.close(write_fd)
                    def worker():
                        try:
                            poller = select.poll()
                            poller.register(listener, select.POLLIN)
                            poller.register(read_fd, select.POLLIN)
                            entered.set()
                            result.extend(poller.poll(3000))
                        except BaseException as error:
                            errors.append(error)
                    thread = threading.Thread(target=worker, daemon=True)
                    try:
                        thread.start()
                        self.assertTrue(entered.wait(3))
                        if not stop_before_wait:
                            os.close(write_fd)
                        thread.join(4)
                        self.assertFalse(thread.is_alive(), "pipe HUP must wake poll")
                        self.assertFalse(errors, errors)
                        self.assertTrue(any(fd == read_fd and mask & select.POLLHUP for fd, mask in result), result)
                    finally:
                        os.close(read_fd)

    def test_readiness_can_disappear_but_accept_never_blocks(self):
        with tempfile.TemporaryDirectory() as tmp:
            with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as listener:
                path = str(Path(tmp) / "input.sock")
                listener.bind(path)
                listener.listen(1)
                listener.setblocking(False)
                with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
                    client.connect(path)
                    poller = select.poll()
                    poller.register(listener, select.POLLIN)
                    self.assertTrue(poller.poll(1000))
                    peer, _ = listener.accept()
                    peer.close()
                    # Simulates disappearing readiness. A blocking listener would hang here.
                    with self.assertRaises(BlockingIOError):
                        listener.accept()


if __name__ == "__main__":
    unittest.main()
