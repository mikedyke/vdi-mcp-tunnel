import base64, json, os, sys, unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from vdi_tunnel import jobs


def term(text, code=0):
    return json.dumps({"command_exit_code": code, "command_output": text})


class FakeIde:
    """Scripted stand-in for the tunnel: answers terminal/run calls in order."""
    def __init__(self, polls, summary="total=3 failed=0 skipped=0"):
        self.polls = list(polls); self.summary = summary; self.calls = []

    def __call__(self, name, args):
        self.calls.append((name, args))
        if name == "execute_run_configuration":
            return "{}"
        script = base64.b64decode(args["command"].split()[-1]).decode("utf-16-le")
        script = script.removeprefix(jobs.PS_PREFIX)
        if script == jobs.NOW_SCRIPT:
            return term("2026-10-15T12:00:00.0000000Z\n")
        if "'fresh='" in script:
            return term(self.polls.pop(0))
        return term(self.summary)


class RunTests(unittest.TestCase):
    ARGS = {"projectPath": "C:/p/", "resultsDir": "m/build/test-results/test",
            "configurationName": "Tests"}

    def run_it(self, ide, args=None, ticks=None):
        sleeps, msgs = [], []
        t = iter(ticks or range(0, 10 ** 6, 10))
        out = jobs.run_tests(ide, args or self.ARGS, msgs.append, sleeps.append, lambda: next(t))
        return out, sleeps, msgs

    def test_waits_until_fresh_results_and_no_workers_then_summarises(self):
        ide = FakeIde(["fresh=0\nworkers=1", "fresh=0\nworkers=1", "fresh=0\nworkers=0",
                       "fresh=5\nworkers=1", "fresh=5\nworkers=0"], summary="total=9 failed=1 skipped=0\nA.b :: boom")
        (text, err), sleeps, _ = self.run_it(ide)
        self.assertFalse(err)
        self.assertIn("A.b :: boom", text)
        self.assertEqual(sleeps, [30, 45, 60, 90, 120])
        names = [c[0] for c in ide.calls]
        self.assertEqual(names[1], "execute_run_configuration")
        self.assertFalse(ide.calls[1][1]["waitForExit"])
        self.assertEqual(names.count("execute_terminal_command"), 1 + 5 + 1)

    def test_backoff_stays_at_cap(self):
        ide = FakeIde(["fresh=0\nworkers=0"] * 7 + ["fresh=1\nworkers=0"])
        _, sleeps, _ = self.run_it(ide)
        self.assertEqual(sleeps, [30, 45, 60, 90, 120, 120, 120, 120])

    def test_timeout_reports_last_status(self):
        ide = FakeIde(["fresh=0\nworkers=1"] * 50)
        (text, err), _, _ = self.run_it(ide, dict(self.ARGS, timeoutMinutes=1),
                                        ticks=iter(range(0, 1000, 30)))
        self.assertTrue(err)
        self.assertIn("timed out", text)
        self.assertIn("'fresh': '0'", text)

    def test_run_point_arguments(self):
        ide = FakeIde(["fresh=1\nworkers=0"])
        args = {"projectPath": "C:/p", "resultsDir": "r", "filePath": "a/B.java", "line": 36}
        self.run_it(ide, args)
        run = ide.calls[1][1]
        self.assertEqual((run["filePath"], run["line"]), ("a/B.java", 36))
        self.assertNotIn("configurationName", run)

    def test_needs_a_target(self):
        (text, err), _, _ = self.run_it(FakeIde([]), {"projectPath": "C:/p", "resultsDir": "r"})
        self.assertTrue(err)


class Helpers(unittest.TestCase):
    def test_ps_command_is_utf16_base64(self):
        cmd = jobs.ps_command("'ä' + 1")
        self.assertTrue(cmd.startswith("powershell -NoProfile -EncodedCommand "))
        self.assertEqual(base64.b64decode(cmd.split()[-1]).decode("utf-16-le"),
                         jobs.PS_PREFIX + "'ä' + 1")
        self.assertNotIn("cmd", cmd.lower().replace("command", ""))

    def test_scripts_quote_paths_safely(self):
        s = jobs.poll_script("C:/it's/x", "2026-10-15T12:00:00Z")
        self.assertIn("'C:/it''s/x'", s)

    def test_terminal_output_unwraps_and_raises_on_nonzero(self):
        self.assertEqual(jobs.terminal_output(term("hi")), "hi")
        with self.assertRaises(RuntimeError):
            jobs.terminal_output(term("bad", code=1))

    def test_is_finished(self):
        self.assertFalse(jobs.is_finished({"fresh": "0", "workers": "0"}))
        self.assertFalse(jobs.is_finished({"fresh": "3", "workers": "2"}))
        self.assertTrue(jobs.is_finished({"fresh": "3", "workers": "0"}))
        self.assertFalse(jobs.is_finished({}))


if __name__ == "__main__":
    unittest.main()
