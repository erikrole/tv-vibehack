# App marks

The launcher bundles a small selection of vector paths from [Simple Icons](https://github.com/simple-icons/simple-icons), a CC0 icon collection. Marks are local Android vector resources; no image service or runtime dependency is used.

| Resources | Pinned source |
| --- | --- |
| YouTube, YouTube TV, Apple TV, Netflix, MLB, Google Play | [Simple Icons 16.0.0](https://github.com/simple-icons/simple-icons/tree/16.0.0/icons) |
| Hulu, Prime Video | [Simple Icons 9.0.0](https://github.com/simple-icons/simple-icons/tree/9.0.0/icons) |

Path geometry is preserved. Each vector's viewport is fitted to the actual mark bounds so wordmarks retain their proportions without an opaque banner or surrounding tile. The native Settings glyph comes from the existing launcher resources. Unmapped applications use their installed icon, rasterized off the main thread at no more than 256 × 256 pixels and retained in the component cache.

The corresponding [CC0 license](https://github.com/simple-icons/simple-icons/blob/16.0.0/LICENSE.md) remains available with the source collection.
