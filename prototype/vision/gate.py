"""Causal S0/S1 novelty gate. It proposes captures, never object identities or absence."""
from dataclasses import dataclass
import cv2
import numpy as np


def luminance_grid(gray):
    """Match the Android gate's 32x24 grid and its 25-point cell averaging."""
    h, w = gray.shape
    x = (np.arange(32) * w + w // 2) // 32
    y = (np.arange(24) * h + h // 2) // 24
    dx, dy = max(1, w // 160), max(1, h // 120)
    values = np.zeros((24, 32), dtype=np.float32)
    for oy in range(-2, 3):
        for ox in range(-2, 3):
            values += gray[np.clip(y + oy * dy, 0, h-1)[:, None], np.clip(x + ox * dx, 0, w-1)[None, :]]
    return values / (25 * 255)


def grid_difference(old, current):
    a, b = old - old.mean(), current - current.mean()
    gain = np.clip(np.sum(a*b) / max(float(np.sum(b*b)), 1e-6), .6, 1.6)
    difference = np.abs(a - gain*b)
    patches = [difference[y:y+6, x:x+8].mean() for y in range(0,19,3) for x in range(0,25,4)]
    return float(difference.mean()), float(max(patches))


def feature_distance(a, b):
    return float(np.clip(1 - np.dot(a, b), 0, 2))


def novelty(features, bank):
    if not bank:
        return 1.0, 1.0, None
    global_distances = [feature_distance(features[0], reference[0]) for reference in bank]
    nearest = int(np.argmin(global_distances))
    # Search regions in the three nearest past views to tolerate view shifts and revisits.
    indices = np.argsort(global_distances)[:3]
    regions = np.concatenate([bank[i][1:] for i in indices], axis=0)
    local = np.clip(1 - features[1:] @ regions.T, 0, 2).min(axis=1)
    return float(global_distances[nearest]), float(local.max()), nearest


@dataclass
class Decision:
    propose: bool
    reason: str
    global_distance: float = 0.0
    region_distance: float = 0.0


class NoveltyGate:
    def __init__(self, config):
        self.config = config
        self.bank = []
        self.pending = None
        self.pending_ns = 0
        self.pending_count = 0

    def clear_pending(self):
        self.pending = None
        self.pending_count = 0

    def observe(self, features, time_ns):
        whole, local, _ = novelty(features, self.bank)
        changed = (whole >= self.config["global_novelty"] or
                   (local >= self.config["region_novelty"] and whole >= self.config["min_global_for_region"]))
        if not changed:
            self.clear_pending()
            return Decision(False, "familiar_view", whole, local)
        if self.pending is None or feature_distance(features[0], self.pending[0]) > self.config["pending_max_distance"]:
            self.pending = features.copy()
            self.pending_ns = time_ns
            self.pending_count = 1
        else:
            self.pending_count += 1
        persistent = (self.pending_count >= self.config["persistence_samples"] and
                      time_ns - self.pending_ns >= self.config["persistence_seconds"] * 1e9)
        if not persistent:
            return Decision(False, "checking_new_view", whole, local)
        return Decision(True, "initial_coverage" if not self.bank else
                        "new_scene_signature" if whole >= self.config["global_novelty"] else "new_region_signature", whole, local)

    def commit(self, features):
        # Only commit when RGB evidence was actually available to this policy.
        self.bank.append(features.copy())
        if len(self.bank) > self.config["prototype_limit"]:
            self.bank.pop(0)
        self.clear_pending()


class MotionCheck:
    """Coarse camera-motion diagnostic; motion alone never forces a capture or vetoes S1."""
    def __init__(self):
        self.previous = None
        self.orb = cv2.ORB_create(nfeatures=250)
        self.matcher = cv2.BFMatcher(cv2.NORM_HAMMING)

    def observe(self, gray):
        small = cv2.resize(gray, (320,240), interpolation=cv2.INTER_AREA)
        keypoints, descriptors = self.orb.detectAndCompute(small, None)
        result = {"motion_inlier_fraction": None, "camera_shift_fraction": None, "aligned_residual": None}
        if self.previous is not None and descriptors is not None:
            old, old_points, old_descriptors = self.previous
            if old_descriptors is not None and len(descriptors) >= 2:
                pairs = self.matcher.knnMatch(old_descriptors, descriptors, k=2)
                matches = [pair[0] for pair in pairs if len(pair)==2 and pair[0].distance < .75*pair[1].distance]
                if len(matches) >= 8:
                    a = np.float32([old_points[m.queryIdx].pt for m in matches])
                    b = np.float32([keypoints[m.trainIdx].pt for m in matches])
                    matrix, mask = cv2.estimateAffinePartial2D(a, b, method=cv2.RANSAC, ransacReprojThreshold=3)
                    if matrix is not None and mask is not None:
                        warped = cv2.warpAffine(old, matrix, (320,240)).astype(np.float32)/255
                        valid = cv2.warpAffine(np.ones_like(old), matrix, (320,240)) > 0
                        current = small.astype(np.float32)/255
                        if valid.mean() > .5:
                            delta = current[valid] - warped[valid]
                            result = {"motion_inlier_fraction": float(mask.mean()),
                                      "camera_shift_fraction": float(np.linalg.norm(matrix[:,2])/400),
                                      "aligned_residual": float(np.abs(delta - np.median(delta)).mean())}
        self.previous = (small, keypoints, descriptors)
        return result
