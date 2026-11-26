package cn.mythicland.framework.navigation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PathLoadBalancerTest {
    @Test
    void cornerCuttingMembersStillParticipateInSpacingOnTheArrivalRoute() {
        PathLoadBalancer balancing = new PathLoadBalancer(PathLoadBalancer.arrivalRoute(square(), Math.sqrt(2), .16));
        balancing.begin(1);
        balancing.observe(0, 3, 64, 1);
        balancing.observe(1, 4, 64, 1);
        balancing.observe(2, 7, 64, 7);
        balancing.end(.16);
        assertTrue(balancing.speedMultiplier(0) < 1, "Cutting a corner must not disable spacing adjustments");
        assertEquals(1, balancing.speedMultiplier(2), .001, "A bot far inside the route should rejoin at normal speed");
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
}
