package local.byd.arrival;

import java.nio.file.*;
import java.util.*;
import static local.byd.arrival.ProximityGate.Event.*;

/** Dependency-free JVM acceptance tests; run with tools/test-core.sh. */
public class CoreTest {
    static int checks;
    static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Bangcle cipher = new Bangcle(Files.readAllBytes(Path.of(args[0])));
        List<String> vectors = Files.readAllLines(Path.of(args[1]));
        for (int i = 0; i < vectors.size(); i += 2) {
            String plain = new String(Base64.getDecoder().decode(vectors.get(i)), java.nio.charset.StandardCharsets.UTF_8);
            check(cipher.encode(plain).equals(vectors.get(i + 1)), "cipher matches independent pyBYD vector " + i);
            check(cipher.decode(vectors.get(i + 1)).equals(plain), "cipher decrypts independent vector " + i);
        }
        // Signals are given as the margin over the beacon's calibrated strength: -40 dBm at a -65 dBm beacon is +25.
        ArrivalGate gate = new ArrivalGate(60000);
        check(!gate.strongSince(0), "never heard at strength");
        check(!gate.observe(0, 25, true), "no arrival when enabled beside car");
        check(gate.strongSince(0) && !gate.strongSince(1), "a sighting at strength is remembered with its time");
        check(!gate.observe(10000, 25, true) && gate.atCar() && gate.strongSince(10000), "initial strong signal suppressed; you're at the car");
        check(!gate.absent(69000) && gate.absent(70000), "out of range after 60 s without a sighting");
        gate.leftCar();
        check(gate.armed() && !gate.atCar(), "leaving arms it, and leaving isn't checked twice");
        check(!gate.observe(71000, 5, true), "first sample not enough");
        check(!gate.observe(72000, 5, true), "second sample not enough");
        check(gate.observe(73000, 5, true) && !gate.armed() && gate.atCar(), "third stable sample fires once; you're at the car");
        check(!gate.observe(74000, 25, true), "no repeat while nearby");
        check(!gate.absent(100000), "still near the car: not out of range");
        gate.observe(110000, -5, true);
        check(!gate.strongSince(75000), "a sighting under its strength doesn't count as being back at the car");
        check(!gate.absent(160000), "a beacon a little under its strength is still nearby");
        gate.observe(120000, -12, true);
        check(gate.absent(170000), "more than 10 dB under its strength counts as out of range");
        gate.arm();
        check(gate.atCar(), "after an arrival, the beacon going is checked as leaving the car");
        gate.leftCar();
        gate.observe(180000, 5, true); gate.observe(181000, 5, true);
        check(gate.observe(182000, 5, true), "the next arrival fires");
        ArrivalGate resets = new ArrivalGate(60000);
        resets.arm();
        resets.observe(0, 5, true); resets.observe(1000, -20, true); resets.observe(2000, 5, true);
        check(!resets.observe(3000, 5, true), "a weak sighting resets the strong ones in a row");
        check(resets.observe(4000, 5, true), "three strong in a row after it fire");
        ArrivalGate held = new ArrivalGate(60000);
        held.arm();
        held.observe(62000, 5, false); held.observe(63000, 5, false);
        check(!held.observe(64000, 5, false) && held.armed(), "cooldown, trusted Wi-Fi or driving keep it armed without firing");
        check(held.observe(65000, 5, true), "and it fires once allowed");
        ArrivalGate shared = new ArrivalGate(60000);
        shared.arm(); shared.disarm();
        check(shared.atCar(), "the GPS trigger started the car: you're at it");
        shared.observe(62000, 5, true); shared.observe(63000, 5, true);
        check(!shared.observe(64000, 5, true), "the GPS trigger started the car: Bluetooth doesn't start it again");
        shared.arm();
        shared.observe(65000, 5, true); shared.observe(66000, 5, true);
        check(shared.observe(67000, 5, true), "armed by trusted Wi-Fi, a strong beacon fires");
        ArrivalGate sparse = new ArrivalGate(60000);
        sparse.arm();
        sparse.observe(62000, 5, true);
        sparse.observe(90000, 5, true);
        check(!sparse.observe(110000, 5, true), "sparse sightings cannot accumulate an arrival");
        ArrivalGate walkingPast = new ArrivalGate(60000);
        walkingPast.observe(0, -5, true);
        check(!walkingPast.atCar(), "a beacon heard only under its strength doesn't mean you were at the car");
        byte[] apple = new byte[23];
        apple[0] = 0x02; apple[1] = 0x15;
        byte[] uuid = new java.math.BigInteger("2686f39cbada4658854aa62e7e5e8b8d", 16).toByteArray();
        System.arraycopy(uuid, uuid.length - 16, apple, 2, 16);
        apple[19] = 1; apple[22] = (byte) -55;
        String ibeacon = Beacons.iBeaconId(apple);
        check("ibeacon:2686f39c-bada-4658-854a-a62e7e5e8b8d:1:0".equals(ibeacon), "the car's iBeacon is recognised by its UUID, major and minor: " + ibeacon);
        check(Beacons.idFor("1A:35:D9:30:D3:C9", apple).equals(ibeacon), "an iBeacon is matched by its id, since its random address may change");
        check(Beacons.idFor("48:87:2D:9E:60:BC", null).equals("mac:48:87:2D:9E:60:BC"), "an Eddystone or other beacon is matched by its fixed address");
        check(Beacons.iBeaconId(new byte[]{0x10, 0x05, 1, 2, 3}) == null && Beacons.iBeaconId(null) == null, "other Apple data is no iBeacon");
        check(Arrays.equals(Beacons.iBeaconFilter(ibeacon), Arrays.copyOf(apple, 22)), "the scan filter matches exactly that iBeacon's advertisement");
        check(Beacons.iBeaconFilter("mac:48:87:2D:9E:60:BC") == null && Beacons.address("mac:48:87:2D:9E:60:BC").equals("48:87:2D:9E:60:BC"), "an address beacon is filtered by address");
        Beacons.Calibration calibration = Beacons.calibrate(List.of(-60, -62, -58, -61, -59));
        check(calibration.threshold == -63, "calibration: 3 dB under the middle reading, so that spot triggers reliably");
        check(calibration.middle == -60 && calibration.weakest == -62 && calibration.strongest == -58 && calibration.readings == 5, "calibration shows what it heard");
        check(Beacons.calibrate(List.of(-20, -21, -22)).threshold == Beacons.STRONGEST && Beacons.calibrate(List.of(-99, -100, -101)).threshold == Beacons.WEAKEST, "calibration stays within -100 to -30 dBm");
        try { Beacons.calibrate(List.of(-60, -61)); throw new AssertionError("must need 3 readings"); }
        catch (IllegalArgumentException expected) { check(true, "calibration needs at least 3 readings"); }
        List<Beacons.Beacon> chosen = List.of(new Beacons.Beacon(ibeacon, "iBeacon", -63, "Calibrated 18 Nov 14:02"), new Beacons.Beacon("mac:48:87:2D:9E:60:BC", "CP27-60BC", -66, ""));
        List<Beacons.Beacon> saved = Beacons.parse(Beacons.toJson(chosen));
        check(saved.size() == 2 && saved.get(1).label.equals("CP27-60BC") && saved.get(0).threshold == -63, "chosen beacons and their strengths survive saving");
        check(saved.get(0).note.equals("Calibrated 18 Nov 14:02") && saved.get(1).note.isEmpty(), "how each strength was set survives saving");
        check(saved.get(0).withThreshold(-58, "Set by hand").threshold == -58 && saved.get(0).withThreshold(-58, "Set by hand").note.equals("Set by hand"), "a new strength carries its own note");
        check(Beacons.parse("[{\"id\":\"mac:48:87:2D:9E:60:BC\",\"label\":\"CP27-60BC\",\"threshold\":-81}]").get(0).note.startsWith("Calibrated with an earlier version"), "a beacon calibrated before notes existed says so");
        check(Beacons.parse("[{\"id\":\"mac:48:87:2D:9E:60:BC\",\"label\":\"CP27-60BC\",\"threshold\":-70}]").get(0).note.isEmpty(), "an older beacon still at the default shows as the default");
        List<String> calls = new ArrayList<>();
        Routine.run(List.of("OPENDOOR", "OPENAIR"), calls::add);
        check(calls.equals(List.of("OPENDOOR", "OPENAIR")), "runs commands in given order");
        calls.clear(); Routine.run(List.of(), calls::add);
        check(calls.isEmpty(), "empty selection sends nothing");
        try {
            Routine.run(List.of("OPENDOOR", "OPENAIR"), command -> { calls.add(command); throw new Exception("rejected"); });
            throw new AssertionError("failure must propagate");
        } catch (Exception expected) {
            check(calls.equals(List.of("OPENDOOR")), "no retry and stop after failure");
        }
        List<Actions.Custom> customs = List.of(new Actions.Custom("C1", "Leave", List.of("LOCKDOOR", "CLOSEWINDOW")),
            new Actions.Custom("C2", "Arrive", List.of("OPENDOOR", "START", "SEAT_DRIVER")));
        check(Actions.expand(List.of("OPENDOOR", "START"), customs).equals(List.of("OPENDOOR", "START")), "built-ins pass through in order");
        check(Actions.expand(List.of("C2"), customs).equals(List.of("OPENDOOR", "START", "SEAT_DRIVER")), "custom action expands to its steps in order");
        check(Actions.expand(List.of("START", "OPENAIR"), customs).equals(List.of("START")), "only the first A/C start runs");
        check(Actions.expand(List.of("CLOSEAIR"), customs).equals(List.of("CLOSEAIR")), "turn off sends A/C off only");
        check(Actions.arrival("START", customs).equals(List.of("OPENDOOR", "START")), "auto-start unlocks first, then starts");
        check(Actions.arrival(Actions.DEFAULT_ARRIVAL, customs).equals(List.of("OPENDOOR", "START")), "by default, arriving unlocks and starts the car");
        check(Actions.arrival("C1", customs).equals(List.of("OPENDOOR", "C1")), "auto-start unlocks before a custom action");
        check(Actions.arrival("C2", customs).equals(List.of("C2")), "a custom action that unlocks keeps its own order");
        check(Actions.arrival("OPENDOOR", customs).equals(List.of("OPENDOOR")), "auto-start set to Unlock unlocks once");
        check(Actions.parse(Actions.toJson(customs)).get(1).steps.equals(List.of("OPENDOOR", "START", "SEAT_DRIVER")), "custom actions survive saving");
        check(Actions.nextId(customs).equals("C3"), "new custom action gets a fresh id");
        check(Actions.labels(List.of("C1", "FINDCAR"), customs).equals("Leave → Find car"), "labels for built-in and custom");
        try { new Actions.Custom("C9", "Bad", List.of("C1")); throw new AssertionError("custom inside custom must fail"); }
        catch (IllegalArgumentException expected) { check(true, "custom actions hold only built-in steps"); }
        for (String action : Actions.BUILT_IN.keySet())
            check(action.equals("START") || action.equals("SECURE") || action.startsWith("SEAT_") || BydApi.COMMANDS.contains(action), "built-in action is an allowed command: " + action);
        check(Actions.CLOSE_AND_LOCK.equals(List.of("CLOSEAIR", "CLOSEWINDOW", "CLOSETRUNK", "LOCKDOOR")), "close & lock: off, windows, tailgate, then lock");
        for (String step : Actions.CLOSE_AND_LOCK) check(BydApi.COMMANDS.contains(step), "close & lock step is an allowed command: " + step);
        List<String> sent = new ArrayList<>();
        Map<String, Exception> failed = Routine.runAll(List.of("CLOSEAIR", "CLOSEWINDOW", "LOCKDOOR"), command -> {
            sent.add(command);
            if (command.equals("CLOSEAIR")) throw new Exception("rejected");
        });
        check(sent.equals(List.of("CLOSEAIR", "CLOSEWINDOW", "LOCKDOOR")), "run-all keeps going after a failure, so the car still locks");
        check(failed.keySet().equals(Set.of("CLOSEAIR")), "run-all reports only the failed step");
        org.json.JSONObject live = new org.json.JSONObject().put("elecPercent", 84.4).put("enduranceMileage", 355).put("time", 1794038744L);
        check(BydApi.realtimeReady(live) && BydApi.batteryPercent(live) == 84, "live data gives the battery level, rounded");
        check(!BydApi.realtimeReady(new org.json.JSONObject().put("requestSerial", "x")), "an answer holding only the request id isn't ready");
        check(BydApi.batteryPercent(new org.json.JSONObject().put("elecPercent", -1)) == -1, "BYD's -1 means no battery level");
        check(BydApi.batteryPercent(new org.json.JSONObject()) == -1, "a missing battery level is reported as none");
        check(AutoStartSettings.restartKm("") == 700, "blank restart distance uses the default 700 km");
        check(AutoStartSettings.restartKm(" 400 ") == 400, "a sensible restart distance is kept");
        check(AutoStartSettings.restartKm("20") == 100, "the restart distance is at least 100 km");
        check(AutoStartSettings.restartKm("90000") == 5000, "the restart distance is at most 5000 km");
        check(AutoStartSettings.restartKm("far") == 700, "an unreadable restart distance uses the default");
        ClimatePlan plan = ClimatePlan.defaults();
        check(plan.choose(16) == plan.level(0) && plan.level(0).driverHeat == ClimatePlan.HIGH && plan.level(0).passengerHeat == ClimatePlan.OFF && plan.level(0).temperature == 24, "below 17 °C: A/C 24 °C and driver seat heat high");
        check(plan.choose(17) == plan.level(1) && plan.level(1).driverHeat == ClimatePlan.OFF && plan.level(1).temperature == 26, "17 to 20 °C: warm A/C, no seat heat");
        check(plan.choose(20) == plan.level(2) && plan.level(2).temperature == 17 && plan.level(2).fan == 3, "20 to 24 °C: 17 °C, fan 3");
        check(plan.choose(24) == plan.level(3) && plan.choose(27.9) == plan.level(3) && plan.level(3).temperature == 17 && plan.level(3).fan == 5, "24 to 28 °C: 17 °C, fan 5");
        check(plan.choose(28) == plan.level(4) && plan.level(4).temperature == 17 && plan.level(4).fan == 7, "28 °C and above: full cold");
        check(plan.band(0).equals("below 17 °C") && plan.band(2).equals("20 to 24 °C") && plan.band(4).equals("28 °C and above"), "each range says where it starts and ends");
        ClimatePlan raised = plan.withFrom(2, 23);
        check(raised.from(2) == 23 && raised.band(1).equals("17 to 23 °C") && raised.from(3) == 24, "moving where Mild starts ends Cold there too");
        ClimatePlan pushed = plan.withFrom(2, 26);
        check(pushed.from(2) == 26 && pushed.from(3) == 27 && pushed.from(4) == 28, "raising a border past the next pushes that one up, keeping 1 °C");
        ClimatePlan lowered = plan.withFrom(3, 16);
        check(lowered.from(3) == 16 && lowered.from(2) == 15 && lowered.from(1) == 14 && lowered.from(4) == 28, "lowering a border past the one below pushes that one down");
        check(plan.withFrom(4, 99).from(4) == ClimatePlan.MAX_BORDER && plan.withFrom(1, -99).from(1) == ClimatePlan.MIN_BORDER, "borders stay within limits");
        check(plan.withFrom(1, -99).withFrom(4, -99).from(4) == ClimatePlan.MIN_BORDER + 3, "pushed borders always leave room for every range");
        ClimatePlan.Level mild = plan.level(2);
        check(mild.withTemperature(40).temperature == 33 && mild.withTemperature(10).temperature == 17, "A/C stays within what the car accepts, 17–33 °C");
        check(mild.withFan(0).fan == 1 && mild.withFan(9).fan == 7, "fan stays 1–7");
        check(mild.withHeat(ClimatePlan.LOW, ClimatePlan.HIGH).driverHeat == ClimatePlan.LOW && mild.withHeat(ClimatePlan.LOW, ClimatePlan.HIGH).passengerHeat == ClimatePlan.HIGH, "each seat has its own heat level");
        check(mild.withHeat(5, -1).driverHeat == ClimatePlan.HIGH && mild.withHeat(5, -1).passengerHeat == ClimatePlan.OFF, "seat heat stays off, low or high");
        check(ClimatePlan.parse(plan.withLevel(1, plan.level(1).withHeat(ClimatePlan.LOW, ClimatePlan.LOW)).toJson()).level(1).passengerHeat == ClimatePlan.LOW, "seat heat levels survive saving");
        check(ClimatePlan.parse(raised.withLevel(4, plan.level(4).withFan(6)).toJson()).from(2) == 23
            && ClimatePlan.parse(raised.withLevel(4, plan.level(4).withFan(6)).toJson()).level(4).fan == 6, "start settings survive saving");
        check(ClimatePlan.parse("{\"coldBelow\":22,\"warmFrom\":24,\"hotAbove\":27,\"levels\":[]}").from(1) == 17, "old four-level settings switch to the defaults");
        String older = "{\"freezingBelow\":12,\"coldBelow\":12,\"warmFrom\":24,\"hotAbove\":27,\"levels\":["
            + "{\"name\":\"Freezing\",\"temperature\":24,\"fan\":3,\"driverSeat\":true,\"passengerSeat\":false},"
            + "{\"name\":\"Cold\",\"temperature\":15,\"fan\":3,\"driverSeat\":false,\"passengerSeat\":false},"
            + "{\"name\":\"Mild\",\"temperature\":17,\"fan\":3,\"driverSeat\":false,\"passengerSeat\":false},"
            + "{\"name\":\"Warm\",\"temperature\":17,\"fan\":5,\"driverSeat\":false,\"passengerSeat\":false},"
            + "{\"name\":\"Hot\",\"temperature\":17,\"fan\":7,\"driverSeat\":false,\"passengerSeat\":false}]}";
        ClimatePlan converted = ClimatePlan.parse(older);
        check(converted.choose(27) == converted.level(3) && converted.choose(28) == converted.level(4), "older settings keep Hot above 27 °C");
        check(converted.from(1) == 12 && converted.from(2) == 13, "older equal borders are spread 1 °C apart");
        check(converted.level(1).temperature == 17 && converted.level(0).temperature == 24, "older 15 °C becomes 17 °C, which the car accepts; the rest is kept");
        check(converted.level(0).driverHeat == ClimatePlan.HIGH && converted.level(0).passengerHeat == ClimatePlan.OFF, "older seat heat on becomes high, as it was sent");
        try { new ClimatePlan(new int[]{17, 25, 24, 28}, new ClimatePlan.Level[]{mild, mild, mild, mild, mild}); throw new AssertionError("bands must go up"); }
        catch (IllegalArgumentException expected) { check(true, "ranges out of order are rejected"); }
        check(Weather.url(32.0853, 34.7818).equals("https://api.open-meteo.com/v1/forecast?latitude=32.09&longitude=34.78&current=temperature_2m"), "the weather request sends the position rounded to about 1 km");
        check(Weather.currentCelsius("{\"latitude\":32.1,\"current\":{\"time\":\"2026-11-18T15:30\",\"interval\":900,\"temperature_2m\":21.3}}") == 21.3, "reads the current outside temperature");
        java.util.Map<String, Object> air = BydApi.climateParams(21, 17, 5, 30);
        check(air.get("mainSettingTemp").equals(5) && air.get("timeSpan").equals(5) && air.get("windLevel").equals(5), "21 °C on a 17–33 car is scale 5, 30 min, fan 5");
        check(!BydApi.climateParams(20, 17, 0, 10).containsKey("windLevel") && BydApi.climateParams(20, 17, 0, 10).get("timeSpan").equals(1), "fan 0 stays automatic; 10 min is code 1");
        try { BydApi.climateParams(16, 17, 3, 30); throw new AssertionError("below car range must fail"); }
        catch (IllegalArgumentException expected) { check(true, "temperature outside the car range is rejected"); }
        java.util.Map<String, Object> seat = BydApi.seatParams("1", 1, 3);
        check(seat.get("chairType").equals("1") && seat.get("mainHeat").equals(1) && seat.get("copilotHeat").equals(3), "driver seat high, passenger off");
        check(BydApi.seatHeatCommand(ClimatePlan.HIGH) == 1 && BydApi.seatHeatCommand(ClimatePlan.LOW) == 2, "seat heat high and low map to BYD's command scale");
        check(!BydApi.standingStill(new org.json.JSONObject().put("powerGear", 1).put("speed", 12.5)), "moving: someone is driving, so walk-away sends nothing");
        check(BydApi.standingStill(new org.json.JSONObject().put("powerGear", 3).put("speed", 0)), "switched on but standing (remote A/C, or left on): walking away may turn it off");
        check(!BydApi.standingStill(new org.json.JSONObject().put("powerGear", 1)) && !BydApi.standingStill(new org.json.JSONObject().put("speed", -1)), "speed not reported: may be moving, so nothing is sent");
        check(ProximityGate.leftWhereYouWere(40, 8) && ProximityGate.leftWhereYouWere(55, 10), "car still where you were with it, within GPS error: you walked away");
        check(!ProximityGate.leftWhereYouWere(300, 10), "car 300 m from where you parted: someone drove it off, nothing is sent");
        List<History.Entry> recent = new ArrayList<>();
        for (int i = 0; i < 45; i++) recent = History.add(recent, new History.Entry(i, "Widget", "Lock", History.OK, "Confirmed by BYD."));
        check(recent.size() == History.MAX && recent.get(0).at == 44, "recent actions keep the newest 40, newest first");
        recent = History.add(recent, new History.Entry(99, "Bluetooth: CP27-60BC at -52 dBm", "Unlock → Start car", History.SKIPPED, "Not sent"));
        List<History.Entry> reread = History.parse(History.toJson(recent));
        check(reread.get(0).trigger.equals("Bluetooth: CP27-60BC at -52 dBm") && reread.get(0).outcome.equals(History.SKIPPED) && reread.size() == History.MAX, "recent actions and their triggers survive saving");
        check(BydApi.seatCommandLevel(1) == 3 && BydApi.seatCommandLevel(3) == 1 && BydApi.seatCommandLevel(0) == 0, "seat status maps to command scale");
        ProximityGate car = new ProximityGate(15, 30, false);
        check(car.observe(10, 5, true) == NONE, "a new install must see you leave before the first start");
        check(car.observe(40, 5, true) == LEFT && !car.armed(), "leaving beyond 30 m asks for a car check before arming");
        car.carChecked(false);
        check(car.armed(), "car still at its spot: you walked away, so it arms");
        check(car.observe(40, 5, true) == NONE, "armed and away: no more car checks");
        check(car.observe(12, 5, true) == FIRE, "arriving within 15 m starts the car once");
        check(!car.armed() && car.observe(10, 5, true) == NONE, "no repeat while still nearby");
        check(car.observe(20, 5, true) == NONE && !car.armed(), "between the radii stays disarmed");
        check(car.observe(33, 5, true) == NONE, "reading just past 30 m within its accuracy is not leaving");
        check(car.observe(45, 25, true) == NONE, "noisy in-car reading (45 m ±25 m) is not leaving");
        check(car.observe(36, 5, true) == LEFT, "confident got-away reading asks for a car check");
        car.carChecked(true);
        check(!car.armed(), "car moved with you: you drove it, so it stays disarmed");
        check(car.observe(60, 5, true) == LEFT, "still away from the car's new spot: check again");
        check(car.observe(5, 5, true) == NONE, "parked beside the car: no start before you leave it");
        check(car.observe(40, 5, true) == LEFT, "walking away from the new spot asks for a car check");
        car.carChecked(false);
        check(car.armed() && car.observe(14, 5, true) == FIRE, "back at the car's new spot, it starts");
        check(new ProximityGate(15, 30, false).observe(5, 5, true) == NONE, "restart from saved disarmed state does not repeat the start");
        ProximityGate noisy = new ProximityGate(15, 30, true);
        check(noisy.observe(10, 40, true) == NONE && noisy.armed(), "fix worse than 30 m accuracy is ignored");
        check(noisy.observe(10, 5, true) == FIRE, "accurate fix after noisy ones still starts");
        ProximityGate cooling = new ProximityGate(15, 30, true);
        check(cooling.observe(10, 5, false) == NONE && cooling.armed(), "cooldown keeps the trigger armed");
        check(cooling.observe(10, 5, true) == FIRE, "trigger fires once the cooldown has passed");
        ProximityGate byWifi = new ProximityGate(15, 30, false);
        byWifi.arm();
        check(byWifi.observe(20, 5, true) == NONE && byWifi.armed(), "trusted Wi-Fi arms before the start distance");
        check(byWifi.observe(10, 5, true) == FIRE, "armed by Wi-Fi, the trigger fires when the phone gets close");
        ProximityGate home = new ProximityGate(15, 30, true);
        check(home.observe(5, 5, false) == NONE && home.armed(), "on trusted Wi-Fi beside the car: no start, still armed");
        check(home.observe(40, 5, true) == LEFT, "leaving the car while armed still checks whether you drove it");
        home.carChecked(true);
        check(!home.armed(), "drove off without a start: disarmed until you leave the car's new spot");
        // carCheck(moved from saved spot, re-arm, phone to car, moving fast now, travelling this trip, follow-only, were at the car)
        check(ProximityGate.carCheck(152, 20, 30, true, true, false, false) == ProximityGate.CarCheck.MOVED_WITH_YOU,
            "car moved and is 30 m from you while you travel: you're driving it");
        check(ProximityGate.carCheck(2140, 20, 2515, true, true, false, false) == ProximityGate.CarCheck.MOVED_WITHOUT_YOU,
            "car moved 2.1 km while 2.5 km from you: someone else drove it (seen 17:16), so only its position is updated");
        check(ProximityGate.carCheck(1064, 20, 1055, false, false, true, false) == ProximityGate.CarCheck.MOVED_WITHOUT_YOU,
            "car moved 1 km while you stayed home: someone else drove it (seen 10:13)");
        check(ProximityGate.carCheck(2100, 20, 40, false, false, true, false) == ProximityGate.CarCheck.MOVED_WITHOUT_YOU,
            "car brought back home while you're home, not travelling: someone else drove it, so no 'with the car'");
        check(ProximityGate.carCheck(5, 20, 30, false, false, false, true) == ProximityGate.CarCheck.WALKED_AWAY,
            "car at its spot, you on foot after being at the car: you walked away");
        check(ProximityGate.carCheck(5, 20, 2203, false, false, false, false) == ProximityGate.CarCheck.AWAY,
            "car at its spot, you away but never at the car: arm only, no walk-away (seen 17:18: it locked your wife's car)");
        check(ProximityGate.carCheck(9, 20, 20, true, true, false, true) == ProximityGate.CarCheck.UNSURE,
            "car at its spot while you move at 26 km/h: just driven off with BYD lagging, never walking away (seen 08:06)");
        check(ProximityGate.carCheck(5, 20, 30, false, true, true, true) == ProximityGate.CarCheck.UNSURE,
            "follow-only check: still in traffic looks like parked, so it never arms");
        check(ProximityGate.carIsHere(25, 15) && !ProximityGate.carIsHere(2100, 15),
            "before unlocking on arrival, the car must really be within the start distance plus 30 m of you");
        check(ProximityGate.vehicleCheckGapMs(0) == 60_000, "in a vehicle, the car is checked every minute, so a 4-minute drive is followed");
        check(ProximityGate.vehicleCheckGapMs(1) == 120_000, "car still at its spot while you move: wait longer before asking again");
        check(ProximityGate.vehicleCheckGapMs(30) == 15 * 60_000, "on a long train ride, at most every 15 minutes");
        check(ProximityGate.restartDue(800_000, 700, 10 * 60_000), "800 km from the car is beyond 700 km: restart tracking");
        check(!ProximityGate.restartDue(26_000, 700, 10 * 60_000), "a real 26 km drive is no error");
        check(!ProximityGate.restartDue(800_000, 700, 2 * 60_000), "at most one restart every 5 minutes, so a stuck error can't loop");
        check(!ProximityGate.restartDue(500_000, 700, 10 * 60_000) && ProximityGate.restartDue(500_000, 450, 10 * 60_000),
            "a wrong 500 km reading is caught with the distance set to 450 km (Israel is about 420 km long), not with 700");
        check(!ProximityGate.movingFast(true, 17 / 3.6f, 235, 20), "17 km/h from a ±235 m reading at home is noise, not driving (seen 20:29)");
        check(ProximityGate.movingFast(true, 30 / 3.6f, 6, 20), "30 km/h from a ±6 m reading is driving");
        check(!ProximityGate.movingFast(true, 6 / 3.6f, 5, 20) && !ProximityGate.movingFast(false, 0, 5, 20), "walking, or no speed, is not driving");
        check(Routine.retryable(new java.net.SocketTimeoutException(), false), "a network timeout before any command (PIN check, seen 20:16) is tried once more");
        check(!Routine.retryable(new java.net.SocketTimeoutException(), true), "a timeout after commands started is never retried: the car may have acted");
        check(!Routine.retryable(new java.io.IOException("BYD API rejected request (code 5011); no retry"), false),
            "a refusal from BYD, such as a wrong PIN, is never retried");
        ProximityGate passing = new ProximityGate(15, 30, true);
        check(passing.observe(80, 5, true) == NONE, "armed and never near the car: no car checks");
        check(Math.abs(ProximityGate.metres(32.0, 34.0, 32.0001, 34.0) - 11.12) < 0.05, "0.0001 degrees latitude is about 11 m");
        check(Regions.COUNTRIES.size() == 101 && Regions.SERVERS.size() == 16, "all countries and servers are listed");
        check(Regions.country("IL").server.equals("eu") && Regions.host("eu").equals("https://dilinkappoversea-eu.byd.auto"), "Israel stays on the Europe server");
        check(Regions.country("AU").server.equals("au") && Regions.country("AE").server.equals("no") && Regions.country("TH").server.equals("sg"), "countries map to their region's server");
        Set<String> codes = new HashSet<>();
        for (Regions.Country country : Regions.COUNTRIES) {
            check(codes.add(country.code), "country code is unique: " + country.code);
            check(Regions.SERVERS.containsKey(country.server), "country uses a known server: " + country.code);
        }
        check(Regions.preferredCountry("il", "US").equals("IL"), "SIM country is preferred");
        check(Regions.preferredCountry("", "de").equals("DE"), "language setting is the next choice");
        check(Regions.preferredCountry("", "").equals("GB"), "unknown phone country falls back to a listed one");
        try { Regions.host("evil.example.com/"); throw new AssertionError("must reject unknown server"); }
        catch (IllegalArgumentException expected) { check(true, "only listed servers are accepted"); }
        AutoStartSettings values = new AutoStartSettings("", "", "");
        check(values.start == 15 && values.rearm == 20 && values.minutes == 5, "blank values use the defaults: 15 m, 20 m, 5 min");
        values = new AutoStartSettings("25", "60", "10");
        check(values.start == 25 && values.rearm == 60 && values.minutes == 10, "sensible values are kept");
        values = new AutoStartSettings("30", "20", "5");
        check(values.start == 30 && values.rearm == 35, "re-arm is raised clearly beyond the start distance");
        values = new AutoStartSettings("1", "2", "0");
        check(values.start == 3 && values.rearm == 8 && values.minutes == 1, "values below the minimum are raised");
        values = new AutoStartSettings("999", "999", "999");
        check(values.start == 495 && values.rearm == 500 && values.minutes == 60, "values above the maximum are lowered");
        values = new AutoStartSettings("abc", " 18 ", "-3");
        check(values.start == 15 && values.rearm == 20 && values.minutes == 5, "unreadable values use the defaults");
        check(AutoStartSettings.wifiSignal("") == -70, "blank Wi-Fi signal uses the default −70 dBm");
        check(AutoStartSettings.wifiSignal(" -60 ") == -60, "a sensible Wi-Fi signal is kept");
        check(AutoStartSettings.wifiSignal("60") == -60, "a missing minus sign is added");
        check(AutoStartSettings.wifiSignal("−65") == -65, "a typographic minus sign is accepted");
        check(AutoStartSettings.wifiSignal("-10") == -30, "a signal stronger than −30 dBm is lowered");
        check(AutoStartSettings.wifiSignal("-120") == -95, "a signal weaker than −95 dBm is raised");
        check(AutoStartSettings.wifiSignal("strong") == -70, "an unreadable Wi-Fi signal uses the default");
        check(AutoStartSettings.intervalSeconds("") == 5, "blank check interval uses the default 5 s");
        check(AutoStartSettings.intervalSeconds(" 12 ") == 12, "a sensible check interval is kept");
        check(AutoStartSettings.intervalSeconds("0") == 1, "the check interval is at least 1 s");
        check(AutoStartSettings.intervalSeconds("300") == 60, "the check interval is at most 60 s");
        check(AutoStartSettings.intervalSeconds("often") == 5, "an unreadable check interval uses the default");
        check(AutoStartSettings.beaconAbsentMillis(0) == 60000, "nonstop scanning: out of range for 60 s means you left");
        check(AutoStartSettings.beaconAbsentMillis(60) == 120000, "scanning every 60 s: out of range for two rounds means you left");
        check(AutoStartSettings.BEACON_SCAN_SECONDS[0] == 0 && AutoStartSettings.beaconScanLabel(0).startsWith("Nonstop"), "the first beacon choice is nonstop, the default");
        boolean roundsApart = true;
        for (int seconds : AutoStartSettings.BEACON_SCAN_SECONDS) roundsApart &= seconds == 0 || seconds >= 10;
        check(roundsApart, "beacon scan rounds start at least 10 s apart, under Android's limit of 5 scans in 30 s");
        check(AutoStartSettings.beaconScanLabel(30).equals("Every 30 s (listens for 5 s)"), "a beacon scan choice says how often and how long it listens");
        check(UpdateCheck.buildOf("v42") == 42, "release tag holds the build number");
        check(UpdateCheck.newerBuild("v42", 41) == 42, "a higher build on GitHub is an update");
        check(UpdateCheck.newerBuild("v42", 42) == 0, "the same build is not an update");
        check(UpdateCheck.newerBuild("v41", 42) == 0, "an older build on GitHub is not an update");
        for (String bad : List.of("42", "v", "v1.2", "release-42", "v42-beta", ""))
            try { UpdateCheck.buildOf(bad); throw new AssertionError("must reject tag: " + bad); }
            catch (IllegalArgumentException expected) { check(true, "unexpected release tag is rejected"); }
        check(Crypto.md5("hello").equals("5D41402ABC4B2A76B9719D911017C592"), "MD5 reference");
        String key = Crypto.md5(Crypto.md5("test-password"));
        check(Crypto.decrypt(Crypto.encrypt("hello", key), key).equals("hello"), "inner AES roundtrip");
        System.out.println("PASS: " + checks + " core checks");
    }
}
