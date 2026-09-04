# Stable Differential Map Clustering

## Goal

Eliminate visible marker flicker when the map is zoomed or panned. Markers and
clusters that keep the same logical identity must retain the same Yandex MapKit
`PlacemarkMapObject`. Only entities whose membership, presentation, or
visibility changes may be updated, added, or removed.

The existing 20% query padding around the visible map region remains unchanged.

## Current Cause

`CameraMarkerDiff` retains individual camera records, but every change still
calls `ClusterizedPlacemarkCollection.clusterPlacemarks()`. MapKit rebuilds
cluster appearances for the entire collection, so unchanged individual and
group icons visibly disappear and return.

## Clustering Model

Replace `ClusterizedPlacemarkCollection` with a persistent ordinary
`MapObjectCollection`.

Below zoom 14, cameras are assigned to a deterministic Web Mercator grid. The
grid level is the integer part of the current zoom and its cell size corresponds
to the existing 52-pixel clustering radius. Grid coordinates are anchored in
world space, not screen space, so panning does not change membership.

At zoom 14 and above, every camera is represented individually.

An individual entity has the stable key `camera:<camera-id>`. A cluster entity
has the stable key `cluster:<zoom-level>:<cell-x>:<cell-y>`. Cluster members are
sorted by camera ID before signatures are calculated. The cluster position is
the geographic centroid of its current members.

## Differential Rendering

The layout stage produces immutable render entities containing:

- stable key;
- kind: individual or cluster;
- position;
- member IDs;
- presentation signature;
- camera data for an individual entity.

The diff stage compares the desired entities with the currently rendered
entities:

- identical key and signature: perform no MapKit calls;
- same key with changed cluster membership: update only that placemark's
  geometry and cluster icon;
- new key: add one placemark;
- missing key: remove one placemark;
- individual presentation change: update that placemark in place when possible.

Unrelated clusters and individual markers must not receive `setIcon`,
`setGeometry`, removal, or addition calls.

Coverage polygons remain in their existing per-camera differential collection.
The GPS location placemark remains independent from camera markers.

## Data Flow

1. A completed Yandex camera movement requests cameras from SQLite using the
   current padded bounds.
2. The existing generation counter discards stale asynchronous query results.
3. The pure layout component creates stable individual and cluster entities for
   the current zoom.
4. The pure diff component reports additions, removals, in-place updates, and
   unchanged entities.
5. `MainActivity` applies only those operations to the persistent ordinary
   MapKit collection.

When a new camera joins an existing visible cluster, updating that cluster's
count and position is allowed. All unrelated visible entities remain unchanged.

## Release History

Release `4.9.3` will include its own visible change description in the About
dialog. Release `4.9.2` will also be added to the known release history.
The dialog will show the current release description directly below the current
version and list older releases below it without duplicating the current entry.

## Error Handling

An empty query result removes only entities no longer present in the padded
region. A stale query result performs no rendering. Invalid camera coordinates
are skipped by the layout component rather than producing invalid Mercator
coordinates.

## Tests

Pure JVM tests will verify:

- panning at the same zoom preserves cluster keys;
- adding a camera to one cluster changes only that cluster;
- an unrelated cluster remains byte-for-byte unchanged in the diff;
- zoom 14 produces individual camera entities;
- a changed individual is updated rather than forcing unrelated replacements;
- the release history contains the current version and a non-empty description.

The Android release build and an installed-device smoke test will verify cold
startup, repeated zoom and pan operations, About-dialog content, and absence of
`AndroidRuntime` failures.
