package cn.mythicland.framework.navigation;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public final class PathLoadBalancer {
    private static final double DEFAULT_MINIMUM_SPEED_MULTIPLIER = .9;
    private static final double SPEED_RECOVERY_PER_ADJUSTMENT = .03;
    private static final Comparator<Member> ROUTE_ORDER =
            Comparator.comparingDouble((Member member) -> member.phase).thenComparingLong(member -> member.id);

    private final List<Point> route;
    private final Segment[] segments;
    private final double perimeter;
    private final boolean enclosed;
    private final Long2ObjectLinkedOpenHashMap<Member> members = new Long2ObjectLinkedOpenHashMap<>();
    private Member[] snapshot = new Member[0];
    private Member[] ordered = new Member[0];
    private boolean membershipChanged;
    private boolean adjusting;
    private long tick;
    private long nextAdjustmentTick;
    private long adjustmentInterval = 1;
    private double movementRate = 1;

    public PathLoadBalancer(List<Point> route) {
        if (route.isEmpty()) throw new IllegalArgumentException("load balancing requires a nonempty route");
        this.route = List.copyOf(route);
        this.segments = new Segment[route.size()];
        double total = 0, area = 0;
        boolean level = true;
        for (int index = 0; index < route.size(); index++) {
            Point a = route.get(index), b = route.get((index + 1) % route.size());
            double dx = b.x() - a.x(), dz = b.z() - a.z();
            double length = Math.hypot(dx, dz);
            this.segments[index] = new Segment(a, total, dx, dz, length, length * length);
            total += length;
            area += a.x() * b.z() - b.x() * a.z();
            level &= Math.abs(a.y() - route.getFirst().y()) < .01;
        }
        this.perimeter = total;
        this.enclosed = level && route.size() >= 3 && Math.abs(area) > 1;
    }

    /**
     * Approximates the cyclic arrival route, including the average overshoot of a discrete movement step.
     */
    public static List<Point> arrivalRoute(
            List<Point> route,
            double margin,
            double movementStep
    ) {
        if (route.isEmpty()) throw new IllegalArgumentException("arrival route requires a nonempty route");
        if (!Double.isFinite(margin) || margin < 0) {
            throw new IllegalArgumentException("arrival margin must be finite and nonnegative");
        }
        if (!Double.isFinite(movementStep) || movementStep < 0) {
            throw new IllegalArgumentException("movement step must be finite and nonnegative");
        }
        margin = Math.max(0, margin - movementStep * .5);
        if (margin == 0) return List.copyOf(route);
        for (Point point : route) {
            if (Math.abs(point.y() - route.getFirst().y()) >= .01) return List.copyOf(route);
        }
        List<Point> arrivals = new ArrayList<>(route);
        double x = route.getLast().x(), z = route.getLast().z();
        // Settle the cyclic entry positions once, rather than treating each corner cut as a gap error.
        for (int pass = 0; pass < 16; pass++) {
            double previousX = x, previousZ = z;
            for (int index = 0; index < route.size(); index++) {
                Point target = route.get(index);
                double dx = x - target.x(), dz = z - target.z();
                double distance = Math.hypot(dx, dz);
                if (distance > margin) {
                    x = target.x() + dx * margin / distance;
                    z = target.z() + dz * margin / distance;
                }
                arrivals.set(index, new Point(x, target.y(), z));
            }
            if (Math.hypot(x - previousX, z - previousZ) < 1e-6) break;
        }
        return List.copyOf(arrivals);
    }

    public int initialTarget(long ordinal) {
        return (int) Math.floorMod(ordinal, this.route.size());
    }

    public void begin(long tick) {
        this.tick = tick;
    }

    public void observe(long id, double x, double y, double z) {
        Member member = this.members.get(id);
        if (member == null) {
            member = new Member(id);
            this.members.put(id, member);
            this.membershipChanged = true;
        }
        double previousPhase = member.phase;
        boolean wasOnRoute = member.onRoute;
        if (x != member.x || y != member.y || z != member.z) {
            this.project(member, x, y, z);
            member.x = x;
            member.y = y;
            member.z = z;
        }
        member.consecutive = wasOnRoute && member.onRoute && member.observedAt == this.tick - 1;
        if (member.consecutive) {
            double advance = member.phase - previousPhase;
            if (advance > this.perimeter * .5) advance -= this.perimeter;
            if (advance < -this.perimeter * .5) advance += this.perimeter;
            member.travelled += advance;
        } else {
            member.progressTick = this.tick;
            member.travelled = 0;
            member.expectedTravel = 0;
            member.stationary = false;
        }
        member.observedAt = this.tick;
    }

    public int nextWaypoint(double x, double y, double z) {
        return (this.project(new Member(0), x, y, z) + 1) % this.route.size();
    }

    private int project(Member member, double x, double y, double z) {
        double nearest = Double.POSITIVE_INFINITY;
        int nearestSegment = 0;
        for (int index = 0; index < this.segments.length; index++) {
            Segment segment = this.segments[index];
            if (segment.length() == 0) continue;
            Point a = segment.start();
            double offsetX = x - a.x(), offsetZ = z - a.z();
            double fraction =
                    Math.clamp((offsetX * segment.dx() + offsetZ * segment.dz()) / segment.lengthSquared(), 0, 1);
            double distance = Math.pow(offsetX - segment.dx() * fraction, 2)
                    + Math.pow(offsetZ - segment.dz() * fraction, 2);
            if (distance >= nearest) continue;
            nearest = distance;
            nearestSegment = index;
            member.phase = segment.phase() + segment.length() * fraction;
            member.onRoute = distance <= .75 * .75 && Math.abs(y - a.y()) <= .6;
        }
        return nearestSegment;
    }

    public void end(double maximumStep) {
        this.end(maximumStep, 1);
    }

    public void end(double maximumStep, double movementRate) {
        this.end(maximumStep, movementRate, DEFAULT_MINIMUM_SPEED_MULTIPLIER);
    }

    public void end(
            double maximumStep,
            double movementRate,
            double minimumSpeedMultiplier
    ) {
        if (!Double.isFinite(maximumStep) || maximumStep < 0) {
            throw new IllegalArgumentException("maximum movement step must be finite and nonnegative");
        }
        if (!Double.isFinite(movementRate) || movementRate <= 0 || movementRate > 1) {
            throw new IllegalArgumentException("movement rate must be finite and between zero (exclusive) and one");
        }
        if (!Double.isFinite(minimumSpeedMultiplier) || minimumSpeedMultiplier < 0 || minimumSpeedMultiplier > 1) {
            throw new IllegalArgumentException("minimum speed multiplier must be finite and between zero and one");
        }
        if (this.membershipChanged) {
            this.snapshot = this.members.values().toArray(Member[]::new);
            this.ordered = new Member[this.snapshot.length];
            this.membershipChanged = false;
        }
        if (this.movementRate != movementRate) {
            this.movementRate = movementRate;
            this.adjustmentInterval = (long) Math.ceil(1 / movementRate);
            for (long interval = this.adjustmentInterval; interval <= 20; interval++) {
                double steps = movementRate * interval;
                if (Math.abs(steps - Math.rint(steps)) > 1e-9) continue;
                this.adjustmentInterval = interval;
                break;
            }
            this.nextAdjustmentTick = this.tick;
        }
        int count = 0;
        double observationTicks = Math.max(20, Math.ceil(2 / movementRate));
        for (Member member : this.snapshot) {
            if (member.onRoute && member.observedAt == this.tick) {
                if (member.consecutive) member.expectedTravel += maximumStep * movementRate * member.speedMultiplier;
                if (this.tick - member.progressTick >= observationTicks) {
                    if (member.expectedTravel >= .05) {
                        // Measure progress against commanded travel, including the reduced movement cadence.
                        member.stationary = member.travelled < member.expectedTravel * .1;
                    }
                    member.progressTick = this.tick;
                    member.travelled = 0;
                    member.expectedTravel = 0;
                }
                if (member.stationary) member.speedMultiplier = 1;
                else this.ordered[count++] = member;
            } else {
                member.speedMultiplier = 1;
            }
            member.speedMultiplier = Math.max(minimumSpeedMultiplier, member.speedMultiplier);
        }
        if (!this.enclosed || count < 2 || maximumStep == 0) {
            this.adjusting = false;
            for (Member member : this.snapshot) member.speedMultiplier = 1;
            return;
        }
        // Sample a complete movement cycle so staggered steps do not continually change who gives way.
        if (this.tick < this.nextAdjustmentTick) return;
        this.nextAdjustmentTick = this.tick + this.adjustmentInterval;
        double movementPerAdjustment = maximumStep * movementRate * this.adjustmentInterval;
        Arrays.sort(this.ordered, 0, count, ROUTE_ORDER);
        double spacing = this.perimeter / count;
        double origin = Double.POSITIVE_INFINITY;
        double maximumGapError = 0;
        double maximumCoastingGapError = 0;
        for (int index = 0; index < count; index++) {
            double phase = this.ordered[index].phase;
            double offset = phase - index * spacing;
            origin = Math.min(origin, offset);
            Member nextMember = this.ordered[(index + 1) % count];
            double next = nextMember.phase;
            if (index == count - 1) next += this.perimeter;
            double gapError = next - phase - spacing;
            maximumGapError = Math.max(maximumGapError, Math.abs(gapError));
            if (this.adjusting) {
                double coastingError = gapError + movementPerAdjustment / (2 * SPEED_RECOVERY_PER_ADJUSTMENT)
                        * (Math.pow(1 - this.ordered[index].speedMultiplier, 2) -
                        Math.pow(1 - nextMember.speedMultiplier, 2));
                maximumCoastingGapError = Math.max(maximumCoastingGapError, Math.abs(coastingError));
            }
        }
        // Finish early only when recovering normal speed will also leave the gaps close to uniform.
        if (this.adjusting) {
            if (maximumGapError <= spacing * .08 && maximumCoastingGapError <= spacing * .06) this.adjusting = false;
        } else if (maximumGapError > spacing * .12) {
            this.adjusting = true;
        }
        for (int index = 0; index < count; index++) {
            Member member = this.ordered[index];
            // Accumulated compression ahead acts like a one-dimensional collision, without lateral pushes.
            double compression = Math.max(0, member.phase - index * spacing - origin - spacing * .02);
            double desired = 1;
            if (this.adjusting && minimumSpeedMultiplier < 1) {
                // Start recovering before the gap opens: recovery itself still gives up this much travel.
                double slowdown = Math.sqrt(2 * SPEED_RECOVERY_PER_ADJUSTMENT * compression / movementPerAdjustment);
                desired = Math.max(minimumSpeedMultiplier, 1 - slowdown);
            }
            member.speedMultiplier += Math.clamp(desired - member.speedMultiplier, -.04, SPEED_RECOVERY_PER_ADJUSTMENT);
        }
    }

    public double speedMultiplier(long id) {
        Member member = this.members.get(id);
        if (member == null) return 1;
        return member.speedMultiplier;
    }

    public void remove(long id) {
        if (this.members.remove(id) != null) this.membershipChanged = true;
    }

    private record Segment(
            Point start,
            double phase,
            double dx,
            double dz,
            double length,
            double lengthSquared
    ) {
    }

    private static final class Member {
        private final long id;
        private double x = Double.NaN, y, z;
        private double phase;
        private boolean onRoute;
        private long observedAt;
        private double speedMultiplier = 1;
        private boolean consecutive, stationary;
        private long progressTick;
        private double travelled, expectedTravel;

        private Member(long id) {
            this.id = id;
        }
    }

    public record Point(double x, double y, double z) {
        public Point {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("path point coordinates must be finite");
            }
        }
    }
}
