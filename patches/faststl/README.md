# Patches applied to submodules

`0001-erase-key-falls-back-to-iterator.patch` records the local working-tree change to
`include/FastSTL` (submodule pinned at `34f55f9`). The current Android `libMobileGL.so`
builds were produced with this change applied: `erase(key)` uses the iterator-based erase
instead of the in-place fast path added by the pinned submodule commit.

The submodule points at `MobileGL-Dev/FastSTL`, so the change is kept here as a patch
rather than a submodule pointer bump. To reproduce the shipped binary from a clean clone:

```sh
git submodule update --init include/FastSTL
git -C include/FastSTL apply ../patches/faststl/0001-erase-key-falls-back-to-iterator.patch
```

Remove this patch once the change is either upstreamed to `MobileGL-Dev/FastSTL` or proven
unnecessary. The in-place path is faster, so the patch is a deliberate local difference,
not an oversight.
