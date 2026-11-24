package cn.mythicland.framework.navigation;

import cn.mythicland.framework.task.RoundRobinBudget;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PathLoadBalancerTest {
    @Test
    void crossingSelectionPrefersTheGeometricCenterOverShortCutsNearAnEdge() {
        for (List<PathLoadBalancer.Point> route : List.of(circle(), square(), middlePath())) {
            for (int warmup : new int[]{1, 40}) {
                int population = 50;
                Simulation crowded = new Simulation(route, population, true, 1);
                for (int id = 0; id < population; id++) crowded.phases[id] *= .4;
                crowded.run(warmup);
                double centerX = route.stream().mapToDouble(PathLoadBalancer.Point::x).average().orElseThrow();
                double centerZ = route.stream().mapToDouble(PathLoadBalancer.Point::z).average().orElseThrow();
                double radius =
                        route.stream().mapToDouble(point -> Math.hypot(point.x() - centerX, point.z() - centerZ)).min()
                                .orElseThrow();
                double centerDistances = 0;
                int crossings = 0;
                for (int id = 0; id < population; id++) {
                    var goal = crowded.balancing.recoveryGoal(id);
                    if (!crowded.balancing.isCrossing(id)) continue;
                    PathLoadBalancer.Point start = crowded.point(crowded.phases[id]);
                    PathLoadBalancer.Point destination = goal.orElseThrow();
                    double dx = destination.x() - start.x(), dz = destination.z() - start.z();
                    double fraction = Math.clamp(
                            ((centerX - start.x()) * dx + (centerZ - start.z()) * dz)
                                    / (dx * dx + dz * dz), 0, 1
                    );
                    double centerDistance =
                            Math.hypot(start.x() + dx * fraction - centerX, start.z() + dz * fraction - centerZ);
                    centerDistances += centerDistance;
                    crossings++;
                }
                assertTrue(crossings > 0);
                assertTrue(
                        centerDistances / crossings < radius / 3,
                        "Predicted crossing lines should pass near the path center instead of cutting along its edge; distance="
                                + centerDistances / crossings + ", warmup=" + warmup + ", route=" + route
                );
            }
        }
    }

    @Test
    void committedCrossingKeepsItsDestinationUntilPhysicallyReached() {
        for (List<PathLoadBalancer.Point> route : List.of(circle(), square(), middlePath())) {
            Simulation crowded = new Simulation(route, 50, true, 1);
            for (int id = 0; id < crowded.phases.length; id++) crowded.phases[id] *= .35;
            crowded.run(40);
            int selected = -1;
            PathLoadBalancer.Point destination = null;
            for (int id = 0; id < crowded.phases.length; id++) {
                var goal = crowded.balancing.recoveryGoal(id);
                if (!crowded.balancing.isCrossing(id)) continue;
                selected = id;
                destination = goal.orElseThrow();
                break;
            }
            assertTrue(selected >= 0, "The crowded patrol must start a crossing");
            PathLoadBalancer.Point start = crowded.point(crowded.phases[selected]);
            double previousSpeed = crowded.balancing.speedMultiplier(selected);
            boolean slowed = false;
            for (int step = 1; step <= 12; step++) {
                crowded.balancing.begin(40 + step);
                for (int id = 0; id < crowded.phases.length; id++) {
                    PathLoadBalancer.Point point =
                            crowded.point(crowded.perimeter * id / crowded.phases.length + step * .16);
                    if (id == selected) {
                        double fraction = .2 + step * .025;
                        point = new PathLoadBalancer.Point(
                                start.x() + (destination.x() - start.x()) * fraction, start.y(),
                                start.z() + (destination.z() - start.z()) * fraction
                        );
                    }
                    crowded.balancing.observe(id, point.x(), point.y(), point.z());
                }
                double minimumSpeed = .9;
                if (step > 6) minimumSpeed = .95;
                crowded.balancing.end(.16, 1, minimumSpeed);
                assertTrue(
                        crowded.balancing.isCrossing(selected),
                        "Rebalanced peers must not cancel a crossing halfway through"
                );
                assertEquals(
                        destination,
                        crowded.balancing.recoveryGoal(selected).orElseThrow(),
                        "An admitted shortcut must keep its destination instead of turning back toward its original edge"
                );
                double speed = crowded.balancing.speedMultiplier(selected);
                assertTrue(
                        speed >= minimumSpeed && speed <= 1,
                        "Crossing timing must respect the configured speed limits"
                );
                assertTrue(speed >= previousSpeed - .040001, "Crossing speed corrections should remain gradual");
                if (speed < .99) slowed = true;
                previousSpeed = speed;
            }
            assertTrue(
                    slowed,
                    "A crossing arriving ahead of its moving gap should slow down without changing its destination"
            );
            crowded.balancing.begin(53);
            for (int id = 0; id < crowded.phases.length; id++) {
                PathLoadBalancer.Point point = crowded.point(crowded.perimeter * id / crowded.phases.length);
                if (id == selected) point = destination;
                crowded.balancing.observe(id, point.x(), point.y(), point.z());
            }
            crowded.balancing.end(.16);
            assertFalse(
                    crowded.balancing.isCrossing(selected),
                    "The shortcut should finish after reaching its committed destination"
            );
        }
    }

    @Test
    void onlyMajorVacanciesStartCrossingsAndAirborneMembersWaitForLanding() {
        for (List<PathLoadBalancer.Point> route : List.of(circle(), square(), middlePath())) {
            for (int population : new int[]{17, 50}) {
                Simulation crowded = new Simulation(route, population, true, 1);
                for (int id = 0; id < population; id++) crowded.phases[id] *= .35;
                crowded.run(1);
                for (int id = 0; id < population; id++) crowded.balancing.recoveryGoal(id);
                int crossing = 0;
                for (int id = 0; id < population; id++) if (crowded.balancing.isCrossing(id)) crossing++;
                assertTrue(
                        crossing > 0 && crossing <= 4,
                        "Major vacancies should receive a bounded batch of shortcuts"
                );
                for (int id = 0; id < population; id++) {
                    if (!crowded.balancing.isCrossing(id)) continue;
                    crowded.balancing.cancelCrossing(id);
                    assertFalse(crowded.balancing.isCrossing(id));
                    assertTrue(
                            crowded.balancing.recoveryGoal(id).isEmpty(),
                            "Rejected shortcuts must not be immediately reassigned"
                    );
                    break;
                }

                Simulation almost = new Simulation(route, population, true, 1);
                almost.phases[0] += almost.perimeter / population * .35;
                almost.run(1);
                crowded.balancing.begin(2);
                for (int id = 0; id < population; id++) {
                    assertTrue(
                            almost.balancing.recoveryGoal(id).isEmpty(),
                            "Small spacing errors must stay on the route"
                    );
                    PathLoadBalancer.Point point = crowded.point(crowded.phases[id]);
                    crowded.balancing.observe(id, point.x(), point.y(), point.z(), false);
                }
                crowded.balancing.end(.16);
                for (int id = 0; id < population; id++) {
                    assertTrue(
                            crowded.balancing.recoveryGoal(id).isEmpty(),
                            "Airborne bots must land naturally before rejoining"
                    );
                    assertFalse(crowded.balancing.isCrossing(id));
                }
            }
        }
    }

    @Test
    void shortcutsRestoreScatteredPatrolsAndStopOnceApproximatelyUniform() {
        for (List<PathLoadBalancer.Point> route : List.of(circle(), square(), middlePath())) {
            for (int population : new int[]{17, 50}) {
                RecoverySimulation recovery = new RecoverySimulation(route, population, true);
                RecoverySimulation alongRoute = new RecoverySimulation(route, population, false);
                recovery.run(200);
                assertTrue(
                        recovery.relocated >= population * .6,
                        "Severe scattering should primarily recover through interior redistribution"
                );
                assertTrue(
                        recovery.peakCrossings > population * .2,
                        "Recovery should not wait for a small crossing minority to finish before sending others"
                );
                recovery.run(600);
                alongRoute.run(800);
                assertTrue(
                        recovery.crossings > 0,
                        "Severely scattered patrols should use the interior to fill vacancies"
                );
                assertTrue(
                        recovery.maximumGapError() + 1 < Math.max(3, population * .1),
                        "Shortcuts should fill major empty arcs: " + recovery.maximumGapError()
                );
                assertTrue(
                        recovery.maximumGapError() < alongRoute.maximumGapError(),
                        "Interior recovery should outperform only slowing down along the perimeter"
                );
                recovery.run(4000);
                assertTrue(recovery.maximumGapError() < .4, "Along-route adjustments should finish the spacing");
                assertTrue(recovery.maximumDistance < .4, "Recovered patrols should return to the perimeter");
                int completedCrossings = recovery.crossings;
                recovery.run(400);
                assertEquals(
                        completedCrossings, recovery.crossings,
                        "Roughly uniform patrols must finish with along-route corrections, without new shortcuts"
                );
                for (int id = 0; id < population; id++) assertFalse(recovery.balancing.isCrossing(id));
            }
        }
    }

    @Test
    void smallPatrolsAlsoSettleAndStopCrossing() {
        for (List<PathLoadBalancer.Point> route : List.of(circle(), square(), middlePath())) {
            for (int population : new int[]{1, 2, 3, 7}) {
                RecoverySimulation recovery = new RecoverySimulation(route, population, true);
                recovery.run(4800);
                assertTrue(
                        recovery.maximumGapError() < .4,
                        "Small patrols should also reach uniform headways; population=" + population
                                + ", corners=" + route.size() + ", error=" + recovery.maximumGapError()
                                + ", crossings=" + recovery.crossings
                );
                int crossings = recovery.crossings;
                recovery.run(400);
                assertEquals(crossings, recovery.crossings, "Settled patrols should remain on their route");
            }
        }
    }

    @Test
    void configuredMinimumAppliesImmediatelyBetweenStaggeredMovementCycles() {
        PathLoadBalancer balancing = new PathLoadBalancer(square());
        for (int tick = 1; tick <= 16; tick++) {
            balancing.begin(tick);
            balancing.observe(0, tick * .01, 64, 0);
            balancing.observe(1, 1 + tick * .01, 64, 0);
            double movementRate = 1;
            if (tick == 16) movementRate = .1;
            balancing.end(.16, movementRate, .5);
        }
        assertEquals(.5, balancing.speedMultiplier(0), .001);
        balancing.begin(17);
        balancing.observe(0, .17, 64, 0);
        balancing.observe(1, 1.17, 64, 0);
        balancing.end(.16, .1, .95);
        assertEquals(
                .95, balancing.speedMultiplier(0), .001,
                "Raising the configured minimum must take effect before the next spacing update"
        );
        balancing.end(.16, .1, 1);
        assertEquals(1, balancing.speedMultiplier(0), .001);
        for (double invalid : new double[]{-.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> balancing.end(.16, 1, invalid));
        }
    }

    @Test
    void distributesInitialTargets() {
        PathLoadBalancer balancing = new PathLoadBalancer(circle());
        int[] starts = new int[8];
        for (int slot = 0; slot < 73; slot++) starts[balancing.initialTarget(slot)]++;
        for (int count : starts) assertTrue(count == 9 || count == 10);
        PathLoadBalancer square = new PathLoadBalancer(square());
        assertEquals(1, square.nextWaypoint(3, 64, .1));
        assertEquals(2, square.nextWaypoint(13.9, 64, 8));
        assertEquals(3, square.nextWaypoint(6, 64, 13.9));
        assertEquals(0, square.nextWaypoint(.1, 64, 5));
    }

    @Test
    void keepsUniformPatrolsAtNormalSpeed() {
        Simulation simulation = new Simulation(circle(), 30, true, 1);
        simulation.run(600);
        simulation.assertUniform(.001);
        for (int id = 0; id < 30; id++) assertEquals(1, simulation.balancing.speedMultiplier(id), .001);
    }

    @Test
    void nearlyUniformPatrolsDoNotBrakeForSmallPositionErrors() {
        Simulation simulation = new Simulation(circle(), 30, true, 1);
        for (int tick = 0; tick < 200; tick++) {
            for (int id = 0; id < simulation.phases.length; id++) {
                double phase = simulation.perimeter * id / simulation.phases.length + tick * Simulation.SPEED
                        + Math.sin(id * Math.PI * 2 / simulation.phases.length)
                        * simulation.perimeter / simulation.phases.length * .05;
                simulation.phases[id] = phase % simulation.perimeter;
            }
            simulation.run(1);
            for (int id = 0; id < simulation.phases.length; id++) {
                assertEquals(
                        1, simulation.balancing.speedMultiplier(id), .001,
                        "Visually uniform patrols should stay at normal speed instead of chasing exact slots"
                );
            }
        }
    }

    @Test
    void slowsGraduallyAndRecoversOnlyToNormalSpeed() {
        PathLoadBalancer balancing = new PathLoadBalancer(square());
        double previous = 1;
        for (int tick = 1; tick <= 10; tick++) {
            balancing.begin(tick);
            balancing.observe(0, tick * .1, 64, 0);
            balancing.observe(1, 10 + tick * .1, 64, 0);
            balancing.end(.16);
            double speed = balancing.speedMultiplier(0);
            assertTrue(
                    speed <= previous && speed >= previous - .05 && speed >= .9,
                    "Slowdown should be gradual and retain at least ninety percent of normal speed"
            );
            previous = speed;
        }
        for (int tick = 11; tick <= 40; tick++) {
            balancing.begin(tick);
            double phase = tick * .1;
            balancing.observe(0, phase, 64, 0);
            balancing.observe(1, 14 - phase, 64, 14);
            balancing.end(.16);
            double speed = balancing.speedMultiplier(0);
            assertTrue(
                    speed + 1e-9 >= previous && speed <= Math.min(1, previous + .05),
                    "Recovery should be gradual and capped at normal speed"
            );
            previous = speed;
        }
        assertEquals(1, balancing.speedMultiplier(0), .001);
    }

    @Test
    void spreadsClusteredBotsOnCircularAndSquarePathsWhileKeepingNearNormalSpeed() {
        for (List<PathLoadBalancer.Point> route : List.of(circle(), square())) {
            for (int population : new int[]{15, 30, 73}) {
                Simulation simulation = new Simulation(route, population, false, 1);
                simulation.settle();
                simulation.assertUniform(.1);
                simulation.run(600);
                simulation.assertUniform(.1);
            }
        }
    }

    @Test
    void matchesCurrentOrderAndSettlesDisplacedOrRandomPatrolsPromptly() {
        for (List<PathLoadBalancer.Point> route : List.of(circle(), square())) {
            for (int population : new int[]{30, 73}) {
                Simulation displaced = new Simulation(route, population, true, 1);
                displaced.phases[0] = displaced.perimeter / population * 2.5;
                displaced.run(200);
                displaced.assertUniform(.1);

                Simulation random = new Simulation(route, population, false, 1);
                java.util.Random source = new java.util.Random(4200);
                for (int id = 0; id < population; id++) {
                    random.phases[id] = source.nextDouble() * random.perimeter;
                }
                random.run(1200);
                random.assertUniform(.1);
            }
        }
    }

    @Test
    void convergesWithStaggeredMovementUpdates() {
        for (int updateInterval : new int[]{2, 3, 10}) {
            Simulation simulation = new Simulation(square(), 30, false, updateInterval);
            simulation.settle();
            simulation.assertUniform(.15);
        }
    }

    @Test
    void rebalancesAfterDeathsAndNewArrivals() {
        Simulation simulation = new Simulation(circle(), 30, true, 1);
        for (int id = 1; id < 15; id++) {
            simulation.active[id] = false;
            simulation.balancing.remove(id);
        }
        simulation.settle();
        simulation.assertUniform(.1);
        for (int id = 1; id < 15; id++) {
            simulation.active[id] = true;
            simulation.phases[id] = simulation.phases[0];
        }
        simulation.settle();
        simulation.assertUniform(.1);
    }

    @Test
    void oneStationaryBotDoesNotBlockTheRestOfThePatrol() {
        for (List<PathLoadBalancer.Point> route : List.of(circle(), square())) {
            for (int updateInterval : new int[]{1, 10}) {
                Simulation simulation = new Simulation(route, 30, true, updateInterval);
                simulation.frozen[10] = true;
                simulation.run(400 * updateInterval);
                double normalTravel = 400 * Simulation.SPEED;
                for (int id = 0; id < simulation.phases.length; id++) {
                    if (id == 10) continue;
                    assertTrue(
                            simulation.travelled[id] > normalTravel * .89,
                            "One stationary patrol must not hold up member " + id + ": " + simulation.travelled[id]
                    );
                }
                simulation.frozen[10] = false;
                simulation.settle();
                simulation.assertUniform(.15);
            }
        }
    }

    @Test
    void backAndForthJitterDoesNotHideAStationaryBot() {
        PathLoadBalancer balancing = new PathLoadBalancer(square());
        for (int tick = 1; tick <= 40; tick++) {
            balancing.begin(tick);
            double jitter = -.05;
            if (tick % 2 == 0) jitter = .05;
            balancing.observe(0, 3 + jitter, 64, 0);
            balancing.observe(1, 14, 64, 6 + tick * .1);
            balancing.end(.16);
        }
        assertEquals(
                1, balancing.speedMultiplier(0), .001,
                "The stationary bot should be released from headway control so the others can pass"
        );
    }

    @Test
    void excludesOffPathAndAirborneBotsAndAcceptsStationaryGroups() {
        PathLoadBalancer balancing = new PathLoadBalancer(square());
        balancing.begin(1);
        balancing.observe(0, 0, 64, 0);
        balancing.observe(1, .1, 64, 0);
        balancing.observe(2, 7, 64, 7);
        balancing.observe(3, 1, 68, 0);
        balancing.end(.16);
        assertTrue(balancing.speedMultiplier(0) < 1);
        assertEquals(1, balancing.speedMultiplier(2));
        assertEquals(1, balancing.speedMultiplier(3));
        balancing.end(0);
        assertEquals(1, balancing.speedMultiplier(0));
        assertThrows(IllegalArgumentException.class, () -> balancing.end(Double.NaN));
    }

    private static List<PathLoadBalancer.Point> circle() {
        List<PathLoadBalancer.Point> route = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            double angle = index * Math.PI / 4;
            route.add(new PathLoadBalancer.Point(Math.cos(angle) * 7, 64, Math.sin(angle) * 7));
        }
        return route;
    }

    private static List<PathLoadBalancer.Point> square() {
        return List.of(
                new PathLoadBalancer.Point(0, 64, 0), new PathLoadBalancer.Point(14, 64, 0),
                new PathLoadBalancer.Point(14, 64, 14), new PathLoadBalancer.Point(0, 64, 14)
        );
    }

    private static List<PathLoadBalancer.Point> middlePath() {
        return List.of(
                new PathLoadBalancer.Point(-6.5, 82, .5), new PathLoadBalancer.Point(-4.5, 82, -4.5),
                new PathLoadBalancer.Point(.5, 82, -6.5), new PathLoadBalancer.Point(5.5, 82, -4.5),
                new PathLoadBalancer.Point(7.5, 82, .5), new PathLoadBalancer.Point(5.5, 82, 5.5),
                new PathLoadBalancer.Point(.5, 82, 7.5), new PathLoadBalancer.Point(-4.5, 82, 5.5)
        );
    }

    private static final class Simulation {
        private static final double SPEED = .16;
        private final List<PathLoadBalancer.Point> route;
        private final PathLoadBalancer balancing;
        private final double[] phases, starts, lengths, travelled, velocities;
        private final boolean[] active, frozen;
        private final double perimeter;
        private final int updateInterval;
        private long tick;

        private Simulation(List<PathLoadBalancer.Point> route, int population, boolean uniform, int updateInterval) {
            this.route = route;
            this.balancing = new PathLoadBalancer(route);
            this.phases = new double[population];
            this.active = new boolean[population];
            this.frozen = new boolean[population];
            this.travelled = new double[population];
            this.velocities = new double[population];
            Arrays.fill(this.active, true);
            this.starts = new double[route.size()];
            this.lengths = new double[route.size()];
            double total = 0;
            for (int index = 0; index < route.size(); index++) {
                PathLoadBalancer.Point a = route.get(index), b = route.get((index + 1) % route.size());
                this.starts[index] = total;
                this.lengths[index] = Math.hypot(b.x() - a.x(), b.z() - a.z());
                total += this.lengths[index];
            }
            this.perimeter = total;
            this.updateInterval = updateInterval;
            if (uniform) {
                for (int id = 0; id < population; id++) this.phases[id] = total * id / population;
            }
        }

        private PathLoadBalancer.Point point(double phase) {
            int index = 0;
            while (index + 1 < this.route.size() && this.starts[index + 1] <= phase) index++;
            double fraction = (phase - this.starts[index]) / this.lengths[index];
            PathLoadBalancer.Point a = this.route.get(index), b = this.route.get((index + 1) % this.route.size());
            return new PathLoadBalancer.Point(
                    a.x() + (b.x() - a.x()) * fraction, a.y(),
                    a.z() + (b.z() - a.z()) * fraction
            );
        }

        private void settle() {
            // Spreading a full cluster at a ten-percent speed difference can take nearly one relative lap.
            int lapTicks = (int) Math.ceil(this.perimeter / (SPEED * .1));
            this.run((lapTicks + 200) * this.updateInterval);
        }

        private void run(int ticks) {
            for (int step = 0; step < ticks; step++) {
                this.balancing.begin(++this.tick);
                for (int id = 0; id < this.phases.length; id++) {
                    if (!this.active[id]) continue;
                    PathLoadBalancer.Point point = this.point(this.phases[id]);
                    this.balancing.observe(id, point.x(), point.y(), point.z());
                }
                this.balancing.end(SPEED, 1.0 / this.updateInterval);
                for (int id = 0; id < this.phases.length; id++) {
                    if (!this.active[id]) continue;
                    double multiplier = this.balancing.speedMultiplier(id);
                    assertTrue(
                            multiplier >= .9 && multiplier <= 1,
                            "Spacing adjustments must retain ninety percent of normal speed without speeding up"
                    );
                    if ((this.tick + id) % this.updateInterval != 0) continue;
                    if (this.frozen[id]) continue;
                    // Normal ground retains motion between steps instead of moving at the commanded speed instantly.
                    this.velocities[id] = this.velocities[id] * .546 + SPEED * multiplier * .454;
                    this.phases[id] = (this.phases[id] + this.velocities[id]) % this.perimeter;
                    this.travelled[id] += this.velocities[id];
                }
            }
        }

        private void assertUniform(double tolerance) {
            double[] sorted = new double[this.phases.length];
            int count = 0;
            for (int id = 0; id < this.phases.length; id++) {
                if (this.active[id]) sorted[count++] = this.phases[id];
            }
            Arrays.sort(sorted, 0, count);
            double spacing = this.perimeter / count;
            for (int index = 0; index < count; index++) {
                double next = sorted[(index + 1) % count];
                if (index == count - 1) next += this.perimeter;
                assertEquals(
                        spacing, next - sorted[index], spacing * tolerance,
                        "Headways should converge for " + count + " patrols"
                );
            }
        }
    }

    private static final class RecoverySimulation {
        private final Simulation geometry;
        private final PathLoadBalancer balancing;
        private final boolean interior;
        private final double[] x, z, vx, vz;
        private final boolean[] crossing, crossed;
        private final RoundRobinBudget budget = new RoundRobinBudget(7);
        private long tick;
        private int crossings;
        private int relocated, peakCrossings;
        private double maximumDistance;

        private RecoverySimulation(List<PathLoadBalancer.Point> route, int population, boolean interior) {
            this.geometry = new Simulation(route, population, true, 1);
            this.balancing = new PathLoadBalancer(route);
            this.interior = interior;
            this.x = new double[population];
            this.z = new double[population];
            this.vx = new double[population];
            this.vz = new double[population];
            this.crossing = new boolean[population];
            this.crossed = new boolean[population];
            double centerX = route.stream().mapToDouble(PathLoadBalancer.Point::x).average().orElseThrow();
            double centerZ = route.stream().mapToDouble(PathLoadBalancer.Point::z).average().orElseThrow();
            for (int id = 0; id < population; id++) {
                PathLoadBalancer.Point point = this.geometry.point(this.geometry.perimeter * .4 * id / population);
                this.x[id] = centerX + (point.x() - centerX) * 1.4;
                this.z[id] = centerZ + (point.z() - centerZ) * 1.4;
            }
        }

        private void run(int ticks) {
            for (int step = 0; step < ticks; step++) {
                this.balancing.begin(++this.tick);
                double height = this.geometry.route.getFirst().y();
                for (int id = 0; id < this.x.length; id++) this.balancing.observe(id, this.x[id], height, this.z[id]);
                this.balancing.end(Simulation.SPEED, .8, .9);
                int updates = this.budget.begin(this.x.length, .8);
                for (int update = 0; update < updates; update++) {
                    int id = this.budget.next(this.x.length);
                    PathLoadBalancer.Point goal = null;
                    if (this.interior) goal = this.balancing.recoveryGoal(id).orElse(null);
                    if (goal == null) {
                        int target = this.balancing.nextWaypoint(this.x[id], height, this.z[id]);
                        goal = this.geometry.route.get(target);
                        if (Math.hypot(goal.x() - this.x[id], goal.z() - this.z[id]) < .18) {
                            goal = this.geometry.route.get((target + 1) % this.geometry.route.size());
                        }
                    }
                    double dx = goal.x() - this.x[id], dz = goal.z() - this.z[id];
                    double distance = Math.hypot(dx, dz);
                    double multiplier = this.balancing.speedMultiplier(id);
                    assertTrue(
                            multiplier >= .9 && multiplier <= 1,
                            "Recovery must preserve the configured speed range"
                    );
                    double acceleration = Simulation.SPEED * multiplier * .454;
                    if (distance > 1e-6) {
                        this.vx[id] += dx / distance * acceleration;
                        this.vz[id] += dz / distance * acceleration;
                    }
                    this.x[id] += this.vx[id];
                    this.z[id] += this.vz[id];
                    this.vx[id] *= .546;
                    this.vz[id] *= .546;
                }
                int activeCrossings = 0;
                for (int id = 0; id < this.x.length; id++) {
                    boolean crossing = this.balancing.isCrossing(id);
                    if (crossing && !this.crossing[id]) this.crossings++;
                    if (crossing) {
                        activeCrossings++;
                        if (!this.crossed[id]) this.relocated++;
                        this.crossed[id] = true;
                    }
                    this.crossing[id] = crossing;
                }
                this.peakCrossings = Math.max(this.peakCrossings, activeCrossings);
            }
        }

        private double maximumGapError() {
            double[] phases = new double[this.x.length];
            this.maximumDistance = 0;
            for (int id = 0; id < this.x.length; id++) {
                double nearest = Double.POSITIVE_INFINITY;
                for (int index = 0; index < this.geometry.route.size(); index++) {
                    PathLoadBalancer.Point a = this.geometry.route.get(index);
                    PathLoadBalancer.Point b = this.geometry.route.get((index + 1) % this.geometry.route.size());
                    double dx = b.x() - a.x(), dz = b.z() - a.z();
                    double fraction = Math.clamp(
                            ((this.x[id] - a.x()) * dx + (this.z[id] - a.z()) * dz)
                                    / (this.geometry.lengths[index] * this.geometry.lengths[index]), 0, 1
                    );
                    double distance = Math.pow(this.x[id] - a.x() - dx * fraction, 2)
                            + Math.pow(this.z[id] - a.z() - dz * fraction, 2);
                    if (distance >= nearest) continue;
                    nearest = distance;
                    phases[id] = this.geometry.starts[index] + this.geometry.lengths[index] * fraction;
                }
                this.maximumDistance = Math.max(this.maximumDistance, Math.sqrt(nearest));
            }
            Arrays.sort(phases);
            double spacing = this.geometry.perimeter / phases.length, error = 0;
            for (int index = 0; index < phases.length; index++) {
                double next = phases[(index + 1) % phases.length];
                if (index == phases.length - 1) next += this.geometry.perimeter;
                error = Math.max(error, Math.abs(next - phases[index] - spacing) / spacing);
            }
            return error;
        }
    }
}
