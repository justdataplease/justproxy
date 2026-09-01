# Third-party notices

The JustProxy gateway includes or adapts the following software:

- **wgslirpy 0.2.0**, copyright Vitaly Shukela and contributors, dual-licensed under MIT OR Apache-2.0. The packet-router structure, per-flow smoltcp adapter, and TCP/UDP bridging are adapted under its MIT option; see `LICENSE-MIT`.
- **BoringTun**, copyright Cloudflare, Inc. and contributors, licensed under BSD-3-Clause.
- **smoltcp**, copyright smoltcp contributors, licensed under the 0-Clause BSD License.
- **Tokio** and related Rust crates, licensed under MIT and/or Apache-2.0 as declared by their packages.

The complete resolved dependency set must be reviewed with `cargo deny check licenses` before a production release. Source distributions and application About/legal screens must preserve the applicable copyright and license texts.
