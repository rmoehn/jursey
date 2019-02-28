DR 2: Automatability
====================

Nomenclature
------------

- When I write about ‘passing’ a pointer, I mean asking a sub-question or giving
  a reply that contains a pointer.

- A ‘reflect’ is the part of a reflection structure that contains entries for a
  workspace's versions and its parent.

  ```
       reflect
       ↓
  {"r" {"parent" …
        "0" {"ws" …}}}
            ↑
            version
  ```


Context
-------

1. For reflection to be correct, the user must be able to infer from the
   reflection structure how the original looked (snapshot property). Currently
   this only holds for reflects and versions. So if you ask "Give me a diff
   of hypertexts $r.-1.children.0.-1.ws.q and $r.-1.children.1.-1.ws.q.", those
   reflected questions will become plain hypertext without any indication of
   whether the pointers in them were originally locked or unlocked.

2. For automation to work well, workspaces need to stay as generic as possible.
   One shouldn't have to reveal more information than is needed to decide the
   next action. In other words, we need finer-grained unlocks.

   Currently, unlocking a version reveals the workspace, the number of its
   children and which action was taken, even though many algorithm steps will
   only be interested in passing along the workspace's question (to get a diff,
   for example), regardless of its content. But if they passed a pointer at only
   the question, the snapshot property would get lost (cf. item 1).

3. In addition to finer-grained unlocks, predictable automatic actions should
   not be prevented by revealing a piece of specific information. For example,
   in "What is the mass of $1 $3 in $5?", one might predict that all three
   pointers need to be unlocked before taking another action.

   ```
   (unlock "q.1") → "What is the mass of [1: 1] $3 in $5?"
   Now we need a separate automation entry for every possible target of $q.1.
   (unlock "q.2") → "What is the mass of [1: 1] [3: three-pound bread] in $5?"
   Now we need a separate automation entry for every possible target of $q.1 and
   $q.3.
   ```

   Better:

   ```
   (unlock ["$q.1" "$q.3" "$q.5"])
   → "What is the mass of [1: 1] [3: three-pound bread] in [5: kg]?"
   The same automation entry applies regardless of the target of $q.1 and $q.3.
   ```

   Multi-unlock also is the only way to have more than one user work on one
   workspace tree.

4. For the system to be easily usable, the user shouldn't have to unlock
   something just to be able to access (unlock or pass) a pointer whose name
   they can predict. For example, if I want to access the current workspace's
   last version's first child's last version's first sub-question, I shouldn't
   have to unlock each step in between. I know that the path must be
   `$r.-1.children.0.-1.ws.sq.0.q`.

5. Also about usability: the syntax should stay easy for easy cases.


Decision
--------

Based on explorations in the files
[09-dream-reflect-rrr.edn](/scenarios/09-dream-reflect-rrr.edn) and
[reflection-diff.repl](/test/reflection-diff.repl).

Note that if we drop support of nested reflection, many of these become easier,
some become obsolete.

1. Everything from within a reflection structure can be passed around without
   losing its snapshot property. You can remove the snapshot property (turning
   the target into plain hypertext) by suffixing the pointer path with `#`.
   Example: `(ask "How about $r.-1.ws.q#?")`
   TODO: Validate this in the nested case.

   Details:
   - Reflected workspaces can be passed around.
   - Only through a reflect the user has access to all reachable parts of the
     reflection graph.
   - When you pass a version, the containing reflect is automatically passed
     along with it. TODO: Should I drop this behaviour?

2. `:max-v` stays unlocked only until it would be incremented. Ie. I unlock
   `r.max-v`, I see it, I issue another command and it's gone again. `:max-v`s
   further down a reflection tree will never be incremented, so they stay
   unlocked.


   ```
   ↓ (unlock "r.max-v")

   {"r" {"parent" :locked
         :max-v 4
         "3" {"children" {"0" {"parent" :locked
                               :max-v 2}}}}}

   ↓ (unlock "r.5.children.0.1")

   {"r" {"parent" :locked
         :max-v :locked ; Would have become 5. → Gets locked.
         "5" {"children" {"0" {"parent" :locked
                               :max-v 2 ; Stays the same. Stays unlocked.
                               "1" {"ws" :locked …}}}}}}
   ```


3. If you unlock a workspace version by its number, that number is shown.

   ```
   {"r" {"parent" :locked
         :max-v 4}

   ↓ (unlock "r.4")

   {"r" {"parent" :locked
         :max-v :locked
         "4" {"ws" :locked …}}
   ```

   You can also access the last reachable version of a workspace as `-1`. After
   you unlock it, it will be shown by that number only if it would be the same
   as `:max-v`. Since `:max-v` changes in the current workspace, unlocking `-1`
   there will show the actual version number:

   ```
   {"r" {"parent" :locked
         :max-v 4}

   ↓ (unlock "r.-1")

   {"r" {"parent" :locked
         :max-v :locked   ; now 5
         "4" {"ws" :locked …}}
   ```

   In a nested workspace `:max-v` stays the same, so `-1` is shown as `-1`:

   ```
   {"r" {"5" {"children" {"0" {"parent" :locked
                               :max-v 4}

   ↓ (unlock "r.5.children.0.-1")

   {"r" {"5" {"children" {"0" {"parent" :locked
                               :max-v 4
                               "-1" {"ws" :locked …}
   ```

4. You can access predictable pointers even when they're not visible:

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

5. You can unlock multiple pointers at once:

   ```
   {"q" "What is the mass of $1 $3 in $5?"}

   ↓ (unlock ["$q.1" "$q.3" "$q.5"])

   {"q" "What is the mass of [1: 1] [3: three-pound bread] in [5: kg]?"}
   ```

6. All entries and pointers in reflection structures begin locked and can be
   unlocked. Differences in lock state between the original and the reflection
   are communicated through symbols after the pointer name. Example:

   ```
   Original:            "What is the capital city of [1: Texas]?"

   Reflected once (v1): "What is the capital city of $1|?"
                        | means that the pointer was originally unlocked.
   After unlock (v2):   "What is the capital city of [1: Texas]?"
                        No difference to the original, so no extra markup.

   v1 reflected:        "What is the capital city of $1|=?"
                        |= means that the pointer was unlocked (|) in the
                        reflected reflected (original) workspace, and that the
                        lock state in the reflected workspace is the same (=) as
                        in the current workspace.
   After unlock:        "What is the capital city of [1=_: Texas]?"
                        =_ means that the pointer was the same (=) in the
                        reflected reflected (original) workspace, and was locked
                        (_) in the reflected workspace.

   v2 reflected:        "What is the capital city of $1||?"
   After unlock:        "What is the capital city of [1: Texas]?"
                        Whenever the markup would only consist of =, it can be
                        left out.
   ```

   Note that this differs from the original model where things that were locked
   in the original could not be unlocked in the reflection. We might continue
   this restriction.

   Outside hypertext the markup is appended to the name as well:

   ```
   {"q|": :locked}
   {"sq" {"0" {…
               "a_" "Some answer that was originally locked."}}}
   {"parent" :locked
    :max-v :locked
    "2_" {"ws" :locked …}}
   ```

   There is one `_`, `|` or `=` for each level of reflection.

   The characters are chosen to be distinct from other characters used in Jursey
   (especially version numbers), easily writable with a US ASCII keyboard
   layout, valid in Clojure keywords and to not be any kind of bracket
   (`[({<>})]`), so as to not confuse text editors.

   Yes, this looks like it might cause lasting mind tangles. But that's what one
   has to expect when dealing with nested reflection. An alternative is to
   support only single-level reflection.

7. If you unlock a reflect that contains exactly one version (must therefore be
   a reflection of reflection, or a reflection structure passed in hypertext),
   the version number itself is locked.

   ```
   {… {"r" {"parent" :locked
            :max-v :locked
            "|*" {"ws" :locked …}}}}
   ```

   You can access the version by its name: `$…r.*` You can unlock the version
   number itself by adding `*` to the name:

   ```
   ↓ (unlock "…r.**")

   {… {"r" {"parent" :locked
            :max-v :locked
            "4" {"ws" :locked …}}}}
   ```

   Note that the lock state markup gets hairy here. The entry name (version
   number or `*` in this case) has to tell whether the entry value was locked or
   unlocked in the original. But it also has to tell whether the name itself was
   locked or unlocked. So we might end up with something like this:

   ```
   {… "_4|" :locked}
   ```

   This means that the version *number* was locked (`_`) in the original, but
   the now-locked *value* was unlocked (`|`). This is arcane, but consistent in
   that the markup always stands before the thing that it tells about.


Status
------

Proposed.


Consequences
------------

The implementation will become much more complicated. The output might become
harder to understand.


References
----------

- Michael Nygard:
  http://thinkrelevance.com/blog/2011/11/15/documenting-architecture-decisions
