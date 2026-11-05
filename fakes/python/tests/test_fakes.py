import io

import pytest

from kuuma_contracts import EVENTS
from kuuma_contracts.testing import assert_publishes
from kuuma_fakes import scenario as scenarios
from kuuma_fakes.cli import main
from kuuma_fakes.fakes import LocalBus
from kuuma_fakes.runner import console_printer, run
from kuuma_server_common.streams import StreamPublisher
from kuuma_server_common.testing import MemoryStreams

REQUIRED = {"luganda-insults", "english-threat", "slow-grooming"}


def test_three_starting_scenarios_exist_and_load():
    assert REQUIRED <= set(scenarios.all_names())
    for name in scenarios.all_names():
        s = scenarios.load(name)
        assert s.messages
        for m in s.messages:
            if m.expect:
                assert m.expect.category and m.expect.severity


@pytest.mark.parametrize("name", sorted(REQUIRED))
def test_scenario_drives_every_fake_with_valid_events(name):
    bus = run(scenarios.load(name))
    types = {e["type"] for e in bus.log}
    assert {
        "sentinel.message.flagged",
        "casefile.case.updated",
        "companion.advice.shown",
        "guardian.alert.sent",
    } <= types
    for ev in bus.log:
        assert_publishes(EVENTS[(ev["type"], 1)].publisher, ev)


def test_luganda_insults_spans_three_days_and_escalates_once():
    bus = run(scenarios.load("luganda-insults"))
    flagged = [e for e in bus.log if e["type"] == "sentinel.message.flagged"]
    assert len(flagged) == 4 and {e["payload"]["language"] for e in flagged} == {"lg"}
    assert len({e["occurredAt"][:10] for e in flagged}) == 3
    assert sum(e["type"] == "casefile.case.escalated" for e in bus.log) == 1


def test_grooming_reports_stages():
    bus = run(scenarios.load("slow-grooming"))
    stages = [
        s for e in bus.log if e["type"] == "sentinel.flag.updated" for s in e["payload"]["groomingSignals"]
    ]
    assert stages[-1] == "meet_or_images"


def test_fake_can_be_switched_off():
    bus = run(scenarios.load("english-threat"), enabled={"sentinel", "casefile"})
    assert not any(e["type"].startswith(("guardian.", "companion.")) for e in bus.log)


def test_bus_refuses_foreign_namespace():
    bus = LocalBus("7a9e2c41-5d3b-4f6a-8e1c-2b3d4e5f6a7b")
    import datetime as dt

    with pytest.raises(PermissionError):
        bus.publisher("companion")("guardian.alert.sent", {}, dt.datetime.now(dt.UTC))


def test_phone_only_events_never_reach_cloud():
    r = MemoryStreams()
    cloud = {b: StreamPublisher(r, b) for b in ("companion", "casefile", "guardian")}
    run(scenarios.load("english-threat"), cloud=cloud)
    assert r.events("guardian.alert.sent") and r.events("casefile.case.updated")
    assert not r.events("sentinel.capture.ready")
    assert not r.events("sentinel.message.flagged")  # where=phone: stays on the phone


def test_console_never_prints_message_text():
    out = io.StringIO()
    s = scenarios.load("english-threat")
    run(s, on_event=console_printer(out))
    for m in s.messages:
        assert m.text not in out.getvalue()


def test_cli_local(capsys):
    assert main(["english-threat", "--local", "--speed", "0"]) == 0
    assert "guardian.alert.sent" in capsys.readouterr().out


def test_guardian_fake_pairs_a_sample_child():
    from kuuma_fakes.fakes import FakeGuardian

    bus = LocalBus("7a9e2c41-5d3b-4f6a-8e1c-2b3d4e5f6a7b")
    FakeGuardian(bus, scenarios.load("english-threat")).announce_sample_child()
    assert [e["type"] for e in bus.log] == ["guardian.pairing.completed", "guardian.policy.updated"]
    for ev in bus.log:
        assert_publishes("guardian", ev)
