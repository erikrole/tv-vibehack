# Current direction

Theater-first, native Android TV home screen with an open canvas, free-standing app logos/wordmarks, and small supporting labels. Avoid grids of rounded cards, banner-heavy Android launcher styling, text-only app navigation, and decorative blur. Sports and room scenes are secondary, unboxed information/actions.

The current redesign is an emulator preview. The physical Basement TV has not been updated as part of this pass.

## Next acceptance gates

- Review the visible simulator's typography, density, and navigation with the user.
- Verify the chosen design on the Hisense at normal viewing distance, including actual app labels and remote behavior.
- After a requested TV update, verify Home Assistant playback reporting, scene calls, sleep timer behavior, and real camera alerts separately from emulator checks.

## Engineering focus

- Keep app discovery asynchronous and skip unchanged list rebinds.
- Keep alert requests, queued notifications, and decoded camera images bounded.
- Preserve exact selected-app identity through package updates and app-to-Home returns.
