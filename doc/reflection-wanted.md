In my view, the following features are necessary for automatable workspace
diffing. Some of the might not need to be implemented in code, but can be
implemented by convention by the users. You might find a simpler way.

(Nomenclature: When I write about ‘passing’ a pointer, I mean asking a
sub-question or giving a reply that contains a pointer.)

- Pass a pointer to an arbitrary part of a reflected structure while retaining
  the information whether that part was originally locked or not.
  - So far we can only pass a reflect or a version while retaining
    locked/unlocked state within. If we pass a pointer from within that, the
    pointer gets laundered. Ie. it loses the original state.
  - It might already be possible to do this using a syntactical convention.

- Make it possible to do skip unlocks to balance the inconvenience of more
  unlock levels.

- Make it possible to point at a whole workspace.

"What about [1: this] and $3?"

0: {r {3 {ws {q "What about $o1 and $c3?"}}}}

;; Should original pointer status be immediately apparent? Or should it be
;; another unlocking step?

(ask "Look at $r.3.ws.q.")

{q "Look at $1."}

1: {q "Look at [1: What about $o1 and $c3?]."}

2: {q "Look at [1: What about [o1: this] and [c3: that]?]."}

;; Feels too arcane.
;; Also want to be possible to launder these when I want to.

Reflecting 1:

{r {3 {children {0 {3 {ws {q

:locked
"Look at $o1."
"Look at [o1: What about $co1 and $cc3?]."
"Look at [o1: What about [co1: this] and [cc3: that]?]."

Reflecting 2:

:locked
"Look at $o1."
"Look at [o1: What about $o1 and $o3?]."
"Look at [o1: What about [oo1: this] and [oc3: that]?]."

Meh, really?
- Could say: If reflection is this arcane, it's not worth it.
- Or: If you want to reflect something reflected, you should expect arcana.
