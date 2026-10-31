# Polyphony (`Petroleum.Polyphony`)

Gives any ordinary node a **voice count**. `PolyNode` wraps N independent
instances of one node type, called voices, and shows them as one box. Before
this, polyphony existed only inside specific nodes: `InstrumentNode N`, and
the Tracker, Piano Roll and MIDI Player sequencers with their `pitchN`/`gateN`
outputs. Playing a custom voice chain polyphonically meant copying it N times
by hand. Now a filter, an envelope, an oscillator or a whole module
(`PatchNode`) can be switched to 2, 4, 8 or 16 voices from its context menu.

## Ports

For a template with inputs `a, b` and output `out`, a 4-voice `PolyNode` has:

| Inputs | Meaning |
|---|---|
| `a`, `b` | **shared**: feeds every voice; carries the knob |
| `a/1`, `b/1` ... `a/4`, `b/4` | **per voice**: overrides the shared input for that voice once wired |

| Outputs | Meaning |
|---|---|
| `out` | the sum of every voice |
| `out/1` ... `out/4` | each voice on its own |

- **Shared names match the mono node's names**, so converting mono to poly
  keeps every existing wire and knob value. With nothing on the per-voice
  ports, a poly node behaves like N identical copies. The summed `out` is N
  times as loud as the mono node, so turn it down to keep the same level.
- **The separator is `/`.** A plain numeric suffix collides: on a 16-step
  Sequencer, `step1` voice 11 and `step11` voice 1 would both be `step111`.
  Collaboration wire keys are split on the *first* `/` (`n3/freq/2`), and node
  ids never contain one, so they still parse. Diesel quotes the names
  (`poly."freq/2"`).
- **A per-voice input defaults to `NaN`**, which means "use the shared input".
  `Patch` fills an unconnected port with its default, so an unwired per-voice
  port reads `NaN` and `process` swaps in the shared sample (`x != x`). A wired
  source that really emits `NaN` also falls back to the shared value.
- **Per-voice ports get no knob.** `NodeView` builds knob rows only for the
  first `sharedInputCount()` inputs. A per-voice port is for a wire, such as
  `Tracker 4`'s `pitch2` into `freq/2`.

## Processing

`process` keeps convention 2 (no allocation):

- `voiceInputs[v][p]` is preallocated at `Patch.DEFAULT_BLOCK_CAPACITY`, and
  each block it is filled with the per-voice or shared sample. The node never
  writes into `inputs`.
- A voice's outputs are the `PolyNode`'s own `out/v` arrays. Only references
  are reassigned per block.
- The summed outputs are built after every voice has run.

Every voice gets the same `NodeContext`, so convention 9 holds per voice. A
`Trigger` voice detects a new session on the same clock a mono one would.

## Marker interfaces

- **`OneShot`**: `PolyNode.create` returns `OneShotPolyNode` when the template
  is a `OneShot`, the same split as `OneShotPatchNode`. It is idle only once
  every voice is idle. A plain `PolyNode` must not be a `OneShot`: an
  always-idle one would end every render early.
- **`Stateful`**: not delegated. The template's state is saved inside the
  template's own JSON (see below). In collaboration, a changed poly node is
  replaced rather than updated in place.

## What cannot be polyphonic

`PolyNode.supports` refuses these, and the constructor enforces it:

- `AudioSink`, `VirtualLineOut`, `RecordingEnd`: `Patch` and `PatchNode` find
  them with `instanceof`, and a wrapped one would break the exactly-one-sink
  rule.
- `ConstantNode`: it is the knob and macro target.
- `MidiInputNode`, `MicInputNode`: hardware inputs.
- Tracker, Piano Roll, MIDI Player, Instrument: these are already
  multi-channel.
- `PolyNode` itself.
- Nodes with no inputs, since nothing could differ between voices.

## Serialization

There is no new node type. A poly node is saved as its **template's** ordinary
JSON plus `"voices": N`:

```
"LFO Sine" lfo { x = 0; y = 0; voices = 4; .frequency = 300; }
```

- `PatchCodec.saveNode` unwraps to the template for the type, the type
  fields and `Stateful` state. It reads `x`, `y` and `portValues` from the
  wrapper, because the knobs belong to the wrapper.
- `loadNode` runs the ordinary type switch (`loadVoice`) N times on the same
  JSON, so every voice is a real independent instance. A `PatchNode` voice
  gets its own inner `Patch`. No format version bump was needed: older files
  have no `voices` key and load as mono.

## Keeping voices in step with the template

Voice 0 **is** the template, the node the editor's controls and screens
edit: the formula box, the wrapper-oscillator combo, Draw, Record and the
subpatch Edit button all unwrap to it. The other voices are copies, made by a
codec round trip (`PatchCodec.withVoices` / `copies`).

- `PatchCodec.syncVoices` re-copies them when the template's **stamp**
  changes. The stamp is the template's encoded JSON plus a hash of its archive
  samples and data blobs, since an edited sample keeps its archive id.
  `GraphEditor.syncVoices()` calls it for every node, before `buildPatch` and
  before closing a subpatch.
- Copying only on a stamp change matters. Nodes keep their DSP state across
  `Patch` rebuilds, and the editor rebuilds on every graph change during
  playback. An unconditional re-copy would reset every voice's envelope and
  filter memory on each wire edit.
- The new voices are built into a fresh array, and one `volatile` field is
  swapped. `process` reads that field once per block, so the old patch, which
  may still be rendering on the audio thread, finishes its block safely.
- **Known ceiling:** an edit inside the node reaches voice 1 at once, but
  reaches the other voices only at the next rebuild (Play, or any graph change
  while playing).
- Closing a poly subpatch rebuilds the `PatchNode` from the edited document
  and re-wraps it with the same voice count (`SynthApp.closeSubpatchEditor`).
  `SubpatchFrame` keeps the outer node so `replaceNode` finds it.

## Editor

- The node's context menu has **Voices**: 1 (mono), 2, 4, 8 or 16. It goes
  through `GraphEditor.convertVoices`, which reuses `convertChannels`. Wires
  and knob values on shared names survive; wires on per-voice ports beyond
  the new count are dropped. It is one undo step.
- As with the other channel conversions, macros bound to the old node's
  knobs are dropped.
- The title reads `<name> (N voices)`. `Docs.keyFor` unwraps, so the "?"
  button opens the template's article.
- **Known ceiling:** every voice adds one port row per input and per output,
  so a 16-voice node with several inputs is tall.
