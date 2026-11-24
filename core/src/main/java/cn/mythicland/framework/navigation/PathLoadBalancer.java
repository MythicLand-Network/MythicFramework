package cn.mythicland.framework.navigation;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class PathLoadBalancer {
    private static final double DEFAULT_MINIMUM_SPEED_MULTIPLIER = .9;
    private static final double SPEED_RECOVERY_PER_ADJUSTMENT = .03;
    private static final Comparator<Member> ROUTE_ORDER =
            Comparator.comparingDouble(Member::formationPhase).thenComparingLong(member -> member.id);

    private final List<Point> route;
    private final Segment[] segments;
    private final double perimeter;
    private final Point center;
    private final boolean enclosed;
    private final boolean crossable;
    private final int recoveryPhase;
    private final Long2ObjectLinkedOpenHashMap<Member> members = new Long2ObjectLinkedOpenHashMap<>();
    private Member[] snapshot = new Member[0];
    private Member[] ordered = new Member[0];
    private Member[] recoveryOrder = new Member[0];
    private final int[] recoveryCandidates = new int[8];
    private final double[] candidateDistances = new double[8];
    private final double[] candidatePhases = new double[8];
    private boolean membershipChanged;
    private boolean adjusting;
    private long tick;
    private long nextAdjustmentTick;
    private long adjustmentInterval = 1;
    private long nextRecoveryTick;
    private boolean recovering;
    private double maximumStep, movementRate = 1, routeSpeed, travelSpeed, recoveryOrigin;

    public PathLoadBalancer(List<Point> route) {
        this(route, 0);
    }

    public PathLoadBalancer(List<Point> route, int recoverySeed) {
        if (route.isEmpty()) throw new IllegalArgumentException("load balancing requires a nonempty route");
        this.recoveryPhase = Math.floorMod(recoverySeed, 5);
        this.route = List.copyOf(route);
        this.segments = new Segment[route.size()];
        double total = 0, area = 0, centerX = 0, centerZ = 0;
        boolean level = true;
        for (int index = 0; index < route.size(); index++) {
            Point a = route.get(index), b = route.get((index + 1) % route.size());
            double dx = b.x() - a.x(), dz = b.z() - a.z();
            double length = Math.hypot(dx, dz);
            this.segments[index] = new Segment(a, total, dx, dz, length, length * length);
            total += length;
            double cross = a.x() * b.z() - b.x() * a.z();
            area += cross;
            centerX += (a.x() + b.x()) * cross;
            centerZ += (a.z() + b.z()) * cross;
            level &= Math.abs(a.y() - route.getFirst().y()) < .01;
        }
        this.perimeter = total;
        this.enclosed = level && route.size() >= 3 && Math.abs(area) > 1;
        if (this.enclosed) this.center = new Point(centerX / (3 * area), route.getFirst().y(), centerZ / (3 * area));
        else this.center = route.getFirst();
        boolean convex = true;
        for (int index = 0; index < this.segments.length; index++) {
            Segment a = this.segments[index], b = this.segments[(index + 1) % this.segments.length];
            if ((a.dx() * b.dz() - a.dz() * b.dx()) * area < -.001) convex = false;
        }
        this.crossable = this.enclosed && convex;
    }

    public int initialTarget(long ordinal) {
        return (int) Math.floorMod(ordinal, this.route.size());
    }

    public void begin(long tick) {
        this.tick = tick;
    }

    public void observe(long id, double x, double y, double z) {
        this.observe(id, x, y, z, true);
    }

    public void observe(
            long id,
            double x,
            double y,
            double z,
            boolean grounded
    ) {
        Member member = this.members.get(id);
        if (member == null) {
            member = new Member(id);
            this.members.put(id, member);
            this.membershipChanged = true;
        }
        double previousPhase = member.phase;
        boolean wasOnRoute = member.onRoute;
        member.grounded = grounded;
        member.advance = 0;
        if (x != member.x || y != member.y || z != member.z) {
            member.nextWaypoint = (this.project(member, x, y, z) + 1) % this.route.size();
            member.x = x;
            member.y = y;
            member.z = z;
        }
        member.consecutive = wasOnRoute && member.onRoute && member.observedAt == this.tick - 1;
        if (member.consecutive) {
            double advance = member.phase - previousPhase;
            if (advance > this.perimeter * .5) advance -= this.perimeter;
            if (advance < -this.perimeter * .5) advance += this.perimeter;
            member.advance = advance;
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

    /**
     * Returns -1 when the member has not been observed on the route in this tick.
     */
    public int nextWaypoint(long id) {
        Member member = this.members.get(id);
        if (member == null || !member.onRoute || member.observedAt != this.tick) return -1;
        return member.nextWaypoint;
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
        member.routeDistanceSquared = nearest;
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
            this.recoveryOrder = new Member[this.snapshot.length];
            this.membershipChanged = false;
        }
        this.maximumStep = maximumStep;
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
        this.updateCrossings();
        int count = 0;
        boolean crossingRecovery = false;
        double observationTicks = Math.max(20, Math.ceil(2 / movementRate));
        for (Member member : this.snapshot) {
            if (member.crossing != null) {
                crossingRecovery = true;
                this.ordered[count++] = member;
            } else if (member.onRoute && member.observedAt == this.tick) {
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
            double phase = this.ordered[index].formationPhase();
            double offset = phase - index * spacing;
            origin = Math.min(origin, offset);
            Member nextMember = this.ordered[(index + 1) % count];
            double next = nextMember.formationPhase();
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
            double compression = Math.max(0, member.formationPhase() - index * spacing - origin - spacing * .02);
            double desired = 1;
            if (member.crossing != null) {
                desired = this.crossingSpeed(member, minimumSpeedMultiplier);
            } else if (this.adjusting && !crossingRecovery && minimumSpeedMultiplier < 1) {
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

    private double crossingSpeed(Member member, double minimumSpeedMultiplier) {
        if (this.routeSpeed <= 1e-6 || minimumSpeedMultiplier == 1) return 1;
        Crossing crossing = member.crossing;
        double remainingPhase =
                this.wrap(crossing.goalPhase - crossing.phase + this.perimeter * .5) - this.perimeter * .5;
        if (remainingPhase <= 0) return 1;
        double arrivalTicks = remainingPhase / this.routeSpeed;
        double travelTicks =
                Math.sqrt(this.distanceSquared(crossing.goalPhase, member.x, member.z)) / this.nominalSpeed();
        return Math.clamp(travelTicks / arrivalTicks, minimumSpeedMultiplier, 1);
    }

    /**
     * Returns the committed shortcut destination, or the nearest route position when displaced off the route.
     */
    public Optional<Point> recoveryGoal(long id) {
        Member member = this.members.get(id);
        if (!this.enclosed || this.maximumStep == 0 || member == null || !this.ready(member)) return Optional.empty();
        if (this.tick >= this.nextRecoveryTick) this.planRecovery();
        if (member.crossing != null) return Optional.of(this.point(member.crossing.goalPhase));
        if (!member.onRoute) return Optional.of(this.point(member.phase));
        return Optional.empty();
    }

    public boolean isCrossing(long id) {
        Member member = this.members.get(id);
        return member != null && member.crossing != null;
    }

    public void cancelCrossing(long id) {
        Member member = this.members.get(id);
        if (member != null) this.finishCrossing(member);
    }

    /**
     * Leaves a budget-deferred member moving normally until a later recovery batch.
     */
    public void deferCrossing(long id) {
        Member member = this.members.get(id);
        if (member == null) return;
        member.crossing = null;
        member.nextCrossingTick = this.tick + 20;
    }

    private boolean ready(Member member) {
        return member.observedAt == this.tick && member.grounded
                && Math.abs(member.y - this.route.getFirst().y()) <= .6;
    }

    private void updateCrossings() {
        double advance = 0, multipliers = 0;
        int count = 0;
        for (Member member : this.snapshot) {
            if (!this.ready(member) || !member.onRoute || !member.consecutive || member.stationary
                    || member.crossing != null || member.advance < 0 || member.advance > this.maximumStep * 1.5)
                continue;
            advance += member.advance;
            multipliers += member.speedMultiplier;
            count++;
        }
        double shift = 0;
        if (count > 0) {
            shift = advance / count;
            this.routeSpeed += (shift - this.routeSpeed) * .1;
            if (advance > 0 && multipliers > 0) {
                double speed = Math.min(this.maximumStep * this.movementRate, advance / multipliers);
                if (this.travelSpeed == 0) this.travelSpeed = speed;
                else this.travelSpeed += (speed - this.travelSpeed) * .1;
            }
        } else this.routeSpeed = 0;
        this.recoveryOrigin = this.wrap(this.recoveryOrigin + shift);
        double arrivalMargin = Math.clamp(this.perimeter / Math.max(1, this.snapshot.length) * .35, .2, .75);
        for (Member member : this.snapshot) {
            Crossing crossing = member.crossing;
            if (crossing == null) continue;
            crossing.phase = this.wrap(crossing.phase + shift);
            if (!this.ready(member) || this.tick >= crossing.deadline
                    || member.onRoute &&
                    this.distanceSquared(crossing.goalPhase, member.x, member.z) <= arrivalMargin * arrivalMargin) {
                this.finishCrossing(member);
            } else if (this.tick - crossing.progressTick >= 40) {
                double dx = member.x - crossing.x, dz = member.z - crossing.z;
                if (dx * dx + dz * dz < .01) this.finishCrossing(member);
                crossing.x = member.x;
                crossing.z = member.z;
                crossing.progressTick = this.tick;
            }
        }
    }

    private void finishCrossing(Member member) {
        member.crossing = null;
        member.nextCrossingTick = this.tick + 100;
    }

    private void planRecovery() {
        this.nextRecoveryTick = this.tick + 5 - Math.floorMod(this.tick - this.recoveryPhase, 5);
        if (!this.crossable) return;
        int count = 0, crossings = 0;
        for (Member member : this.snapshot) {
            if (!this.ready(member)) continue;
            this.recoveryOrder[count++] = member;
            if (member.crossing != null) crossings++;
        }
        if (count < 4) return;
        Arrays.sort(this.recoveryOrder, 0, count, ROUTE_ORDER);
        double spacing = this.perimeter / count;
        double enterGap = spacing * 2.5;
        double exitGap = spacing * 1.6;
        int capacity = Math.min(4, count - crossings);
        for (int attempt = 0; attempt < Math.max(1, capacity); attempt++) {
            int gapIndex = 0;
            double largestGap = 0;
            for (int index = 0; index < count; index++) {
                double gap = this.forwardGap(index, count);
                if (gap <= largestGap) continue;
                largestGap = gap;
                gapIndex = index;
            }
            if (largestGap <= exitGap) this.recovering = false;
            else if (largestGap >= enterGap && !this.recovering) {
                this.recovering = true;
                this.recoveryOrigin = this.recoveryOrder[(gapIndex + 1) % count].formationPhase();
            }
            if (!this.recovering || capacity <= 0) return;
            double midpoint = this.recoveryOrder[gapIndex].formationPhase() + largestGap * .5;
            // Reserve positions on one moving, evenly spaced grid instead of repeatedly bisecting gaps.
            double phase =
                    this.wrap(this.recoveryOrigin + Math.rint((midpoint - this.recoveryOrigin) / spacing) * spacing);
            Point gapCenter = this.point(phase);
            double centerToGap = Math.hypot(gapCenter.x() - this.center.x(), gapCenter.z() - this.center.z());
            Arrays.fill(this.recoveryCandidates, -1);
            Arrays.fill(this.candidateDistances, Double.POSITIVE_INFINITY);
            int source = -1;
            double shortest = Double.POSITIVE_INFINITY;
            double closestCenter = Double.POSITIVE_INFINITY;
            double sourcePhase = phase;
            double arrivalPhase = 0;
            for (int index = 0; index < count; index++) {
                Member member = this.recoveryOrder[index];
                if (member.crossing != null || member.stationary || this.tick < member.nextCrossingTick) continue;
                double mergedGap = this.forwardGap((index + count - 1) % count, count) + this.forwardGap(index, count);
                if (member.onRoute && mergedGap > spacing * 2) continue;
                double phaseOffset = this.wrap(phase - member.phase + this.perimeter * .5) - this.perimeter * .5;
                if (member.onRoute && Math.abs(phaseOffset) < spacing * .75) continue;
                double toCenter = Math.hypot(member.x - this.center.x(), member.z - this.center.z());
                double oppositePhase = member.phase + this.perimeter * .5;
                double centerTravel =
                        toCenter + Math.sqrt(this.distanceSquared(oppositePhase, this.center.x(), this.center.z()));
                double centralPhase = oppositePhase - this.routeSpeed * centerTravel / this.nominalSpeed();
                centralPhase = this.wrap(
                        this.recoveryOrigin + Math.rint((centralPhase - this.recoveryOrigin) / spacing) * spacing
                );
                double gapStart = this.recoveryOrder[gapIndex].formationPhase();
                double centralOffset = this.wrap(centralPhase - gapStart);
                double candidatePhase = phase;
                if (centralOffset >= spacing * .5 && centralOffset <= largestGap - spacing * .5) {
                    candidatePhase = centralPhase;
                } else {
                    centerTravel = toCenter + centerToGap;
                }
                // Back-project a central arrival into a vacant slot before running bounded full interception.
                double predictedPhase = candidatePhase + this.routeSpeed * centerTravel / this.nominalSpeed();
                double distance = this.centerDistanceSquared(predictedPhase, member.x, member.z);
                int slot = this.recoveryCandidates.length - 1;
                if (distance >= this.candidateDistances[slot]) continue;
                while (slot > 0 && distance < this.candidateDistances[slot - 1]) {
                    this.candidateDistances[slot] = this.candidateDistances[slot - 1];
                    this.recoveryCandidates[slot] = this.recoveryCandidates[slot - 1];
                    this.candidatePhases[slot] = this.candidatePhases[slot - 1];
                    slot--;
                }
                this.candidateDistances[slot] = distance;
                this.recoveryCandidates[slot] = index;
                this.candidatePhases[slot] = candidatePhase;
            }
            // Bound interception work even when a group contains hundreds of possible donors.
            for (int slot = 0; slot < this.recoveryCandidates.length; slot++) {
                int index = this.recoveryCandidates[slot];
                if (index < 0) break;
                Member member = this.recoveryOrder[index];
                double candidatePhase = this.candidatePhases[slot];
                double arrival = this.interceptionPhase(candidatePhase, member.x, member.z);
                double direct = Math.sqrt(this.distanceSquared(arrival, member.x, member.z));
                double along = this.wrap(arrival - member.phase) + Math.sqrt(member.routeDistanceSquared);
                if (direct < 1 || direct > along * .85 || along - direct < Math.max(1, spacing * .5)) continue;
                double centerDistance = this.centerDistanceSquared(arrival, member.x, member.z);
                if (centerDistance > closestCenter + 1e-6) continue;
                if (Math.abs(centerDistance - closestCenter) <= 1e-6 && direct >= shortest) continue;
                source = index;
                shortest = direct;
                closestCenter = centerDistance;
                sourcePhase = candidatePhase;
                arrivalPhase = arrival;
            }
            if (source < 0) return;
            Member member = this.recoveryOrder[source];
            double speed = this.nominalSpeed();
            member.crossing = new Crossing(
                    sourcePhase,
                    this.tick + Math.max(100, (long) Math.ceil(this.perimeter * 2 / speed)),
                    member.x, member.z,
                    this.tick,
                    arrivalPhase
            );
            member.speedMultiplier = 1;
            // Insert the reserved position without sorting again for each additional crossing.
            System.arraycopy(this.recoveryOrder, source + 1, this.recoveryOrder, source, count - source - 1);
            int insertion = 0;
            while (insertion < count - 1 && ROUTE_ORDER.compare(this.recoveryOrder[insertion], member) < 0) insertion++;
            System.arraycopy(this.recoveryOrder, insertion, this.recoveryOrder, insertion + 1, count - insertion - 1);
            this.recoveryOrder[insertion] = member;
        }
    }

    private double forwardGap(int index, int count) {
        double next = this.recoveryOrder[(index + 1) % count].formationPhase();
        if (index == count - 1) next += this.perimeter;
        return next - this.recoveryOrder[index].formationPhase();
    }

    private double nominalSpeed() {
        if (this.travelSpeed > 0) return this.travelSpeed;
        return this.maximumStep * this.movementRate;
    }

    private double interceptionPhase(double phase, double x, double z) {
        if (this.routeSpeed == 0) return this.wrap(phase);
        double speed = this.nominalSpeed();
        double low = 0, high = this.perimeter / speed;
        for (int iteration = 0; iteration < 12; iteration++) {
            double time = (low + high) * .5;
            if (this.distanceSquared(phase + this.routeSpeed * time, x, z) > speed * speed * time * time) low = time;
            else high = time;
        }
        return this.wrap(phase + this.routeSpeed * high);
    }

    private Point point(double phase) {
        phase = this.wrap(phase);
        Segment segment = this.segmentAt(phase);
        double fraction = 0;
        if (segment.length() > 0) fraction = (phase - segment.phase()) / segment.length();
        return new Point(
                segment.start().x() + segment.dx() * fraction, segment.start().y(),
                segment.start().z() + segment.dz() * fraction
        );
    }

    private double distanceSquared(double phase, double x, double z) {
        phase = this.wrap(phase);
        Segment segment = this.segmentAt(phase);
        double fraction = 0;
        if (segment.length() > 0) fraction = (phase - segment.phase()) / segment.length();
        double dx = segment.start().x() + segment.dx() * fraction - x;
        double dz = segment.start().z() + segment.dz() * fraction - z;
        return dx * dx + dz * dz;
    }

    private double centerDistanceSquared(double phase, double x, double z) {
        phase = this.wrap(phase);
        Segment segment = this.segmentAt(phase);
        double fraction = 0;
        if (segment.length() > 0) fraction = (phase - segment.phase()) / segment.length();
        double dx = segment.start().x() + segment.dx() * fraction - x;
        double dz = segment.start().z() + segment.dz() * fraction - z;
        double offsetX = this.center.x() - x, offsetZ = this.center.z() - z;
        double lengthSquared = dx * dx + dz * dz;
        double progress = 0;
        if (lengthSquared > 0) progress = Math.clamp((offsetX * dx + offsetZ * dz) / lengthSquared, 0, 1);
        double nearestX = offsetX - dx * progress, nearestZ = offsetZ - dz * progress;
        return nearestX * nearestX + nearestZ * nearestZ;
    }

    private Segment segmentAt(double phase) {
        int low = 0, high = this.segments.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (this.segments[middle].phase() <= phase) low = middle + 1;
            else high = middle;
        }
        return this.segments[Math.max(0, low - 1)];
    }

    private double wrap(double phase) {
        return phase - Math.floor(phase / this.perimeter) * this.perimeter;
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
        private double phase, advance;
        private double routeDistanceSquared;
        private boolean grounded;
        private Crossing crossing;
        private long nextCrossingTick;
        private int nextWaypoint;
        private boolean onRoute;
        private long observedAt;
        private double speedMultiplier = 1;
        private boolean consecutive, stationary;
        private long progressTick;
        private double travelled, expectedTravel;

        private Member(long id) {
            this.id = id;
        }

        private double formationPhase() {
            if (this.crossing != null) return this.crossing.phase;
            return this.phase;
        }
    }

    private static final class Crossing {
        private double phase, x, z;
        private final long deadline;
        private long progressTick;
        private final double goalPhase;

        private Crossing(
                double phase,
                long deadline,
                double x,
                double z,
                long progressTick,
                double goalPhase
        ) {
            this.phase = phase;
            this.deadline = deadline;
            this.x = x;
            this.z = z;
            this.progressTick = progressTick;
            this.goalPhase = goalPhase;
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
