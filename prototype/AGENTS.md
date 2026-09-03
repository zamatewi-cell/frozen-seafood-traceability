# Prototype Instructions

Run the local server yourself and open the preview in the browser available to this environment. Do not give the user server-start instructions when you can run it.

Before making substantial visual changes, use the Product Design plugin's `get-context` skill when the visual source is unclear or no longer matches the current goal. When the user gives durable prototype-specific design feedback, preferences, or decisions, record them in `AGENTS.md`.

## Approved design baseline

- Use a dark navy left navigation, white global toolbar, cool-gray canvas, white cards, ocean blue primary color, and explicit text/icon status cues.
- The batch genealogy must show both many-to-one merge and one-to-many split, with quantities and units that obey mass balance.
- Keep business occurrence time and system record time visually distinct.
- Temperature evaluation is stage-specific and rule-versioned; never draw one global `-18 ℃` limit across incompatible stages.
- Every teaching/demo surface must visibly say that data is simulated and is not real IoT data or third-party certification.
- The consumer surface is a separate responsive H5 view with a strict public-field whitelist.

When implementing from a selected generated mock, treat that image as the source of truth for layout, component anatomy, density, spacing, color, typography, visible content, and hierarchy.

Build app UI in `src/`. Keep `.openai/hosting.json`, `worker/index.js`, `scripts/prepare-sites-build.mjs`, and `tests/sites-worker.test.mjs` intact so the same local prototype can be handed to Sites. Before a Sites handoff, run `npm run build` and `npm run test:sites`; the build must leave `dist/client/index.html`, `dist/server/index.js`, and `dist/.openai/hosting.json`.
