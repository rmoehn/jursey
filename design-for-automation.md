ADR 2: Automatability
=====================

Context
-------

(Nomenclature: When I write about ‘passing’ a pointer, I mean asking a
sub-question or giving a reply that contains a pointer.)

TODO: All these are based on examples. Include the examples.

For automation to work well, workspaces need to stay as generic as possible. One
shouldn't have to reveal more information than is needed to decide the next
action. Further, predictable automatic actions should not be prevented by
revealing a piece of specific information.

For reflection to be correct, the user must be able to infer from the reflection
structure how the original looked (snapshot property).

For the system to be easily usable, the user shouldn't have to unlock something
just to be able to access (unlock or pass) a pointer whose name they can
predict. Further, the syntax should stay easy for easy cases.


Decision
--------

Note that if we drop support of nested reflection, many of these become easier,
some become obsolete.

1. Everything from within a reflection structure can be passed around without
   losing it's snapshot property. You can remove the snapshot property by
   suffixing the pointer path with `#`. Example: `(ask "How about $r.-1.ws.q#?")`
   TODO: Verify this in the nested case.

2. If only one version of a workspace is shown and the version was not previously
   unlocked by number, the version number itself is locked:

   ```
   {"r" {"parent" :locked
         :max-v :locked
         "_" {"ws" :locked …}}}
   ```

   You can access the version by that name: `$r._` You can unlock the version
   number itself by suffixing the name with `_`:

   ```
   ↓ (unlock "r.__")
   {"r" {"parent" :locked
         :max-v :locked
         "4" {"ws" :locked …}}}
   ```

   TODO: Refine this for the nested case.

3. You can access the last reachable version (LRV) of a workspace as `-1`. Unless
   `:max-v` is unlocked or the LRV is unlocked by its actual number, it is shown
   as `-1`:

   ```
   {"r" {"parent" :locked
         :max-v :locked
         "-1" {"ws" :locked …}}}

   vs.

   {"r" {"parent" :locked
         :max-v 5
         "5" {"ws" :locked …}}}
   ```

4. You can access predictable pointers without them being shown:

   ```
   {"q" …
    "r" :locked}

   ↓ (unlock "r.parent.-1.ws.q")

   {"r" {"parent" {"parent" …
                   "-1" {"ws" {"q" "…"}}}
         :max-v 5
         "5" {"ws" :locked …}}}

   ↓ (ask "What about $r.0.act.arg?")

   …
   ```

4. You can unlock multiple pointers at once:

   ```
   {"q" "What is the mass of $1 $3 in $5?"}

   ↓ (unlock ["$q.1" "$q.3" "$q.5"])

   {"q" "What is the mass of [1: 1] [3: three-pound bread] in [5: kg]?"}
   ```

5. All entries and pointers in reflection structures begin locked and can be
   unlocked. Differences in lock state between the original and the reflection
   are communicated through symbols after the pointer name. Example:

   ```
   Original:            "What is the capital city of [1: Texas]?"

   Reflected once (v1): "What is the capital city of $1|?"
                        - `|` means that the pointer was originally unlocked.
   After unlock (v2):   "What is the capital city of [1: Texas]?"
                        - No difference to original, so no extra markup.

   v1 reflected:        "What is the capital city of $1|=?"
                        - `|=` that the pointer was unlocked (`|`) in the
                          reflected reflected (original) workspace, and that the
                          lock state in the reflected workspace is the same (`=`)
                          as in the current workspace.
   After unlock:        "What is the capital city of [1=_: Texas]?"
                        - `=_` means that the pointer was the same (`=`) in the
                          reflected reflected (original) workspace, and was
                          locked (`_`) in the reflected workspace.

   v2 reflected:        "What is the capital city of $1||?"
   After unlock:        "What is the capital city of [1: Texas]?"
                        - Whenever the markup would only consist of `=`, it can
                          be left out.
   ```

   Outside hypertext the markup is prefixed to the entry:

   ```
   {"q": :|locked}
   {"sq" {"0" {…
               "a" "_: Some answer that was originally locked."}}}

   There is one `_`, `|` or `=` for each level of reflection.

   The characters are chosen to be distinct from other characters used in Jursey
   (especially version numbers), easily writable with a US ASCII keyboard
   layout, valid in Clojure keywords and to not be any kind of bracket
   (`[({<>})]`), so as to not confuse text editors.

   Yes, this looks like it might cause lasting mind tangles. But that's what one
   has to expect when dealing with nested reflection. An alternative is to
   support only single-level reflection.

6. `max-v` stays unlocked only until the next version of the workspace. Ie. I
   unlock a `max-v`, I see it, I issue another command and it's gone again.


Status
------

Proposed.


Consequences
------------

The implementation will get much more complicated.


References
----------

- Michael Nygard:
  http://thinkrelevance.com/blog/2011/11/15/documenting-architecture-decisions
