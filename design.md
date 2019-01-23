# Summary of design decisions about reflection

- Pointers that point at reflection structure: Ordinary pointers suffice.

- Rendering:
    - The "r" entry can be data. → Easily usable with the REBL.
    - But when hypertext contains a reflection pointer, the reflection structure
      will be rendered as a string, probably just using newline + spprint +
      newline.

- Copying reflection pointers:
    - Copy only the target node/entry point to the part of the computation tree
      that I want someone to look at. This corresponds to locking all the parent
      and child reflection pointers.
    - Only with pointers to a `:version/*`, copy the parent and the `:version/*`
      together. The effect is a properly rooted reflection structure (→ upwards
      and sideways navigation possible) with one unlocked version, because
      that's what I want to point out for that other workspace.

- Reflecting children and grandchildren:
    - We do this by unlocking children and grandchildren entries in the current
      workspace's reflection structure.
    - Reflected workspace's "r" entries cannot be unlocked, because that would
      contradict the idea of a reflected workspace being a photograph of the
      workspace as the original user saw it.
    - This entails that if a reflected "r" entry is unlocked, it must have been
      unlocked by the workspace it belongs to. Thus we can see what was
      reflected when.
    - See also scenarios/05-reflect-reflect-reflect-3.edn.

- Identifying children:
    - I might have to add :qa/workspace to retain the reference from parent to
      child after an answer is given. This is not crucial, but would make it
      easier to identify the children.
    - See also scenarios/06-reflect-root-earlier-reflect.edn.

- Reachability:
    - The people of the past cannot look into their future, even though it has
      happened since then.
    - x.parent, where x is the workspace belonging to the parent's sub-question
      s: Everything that led to x.0. Ie. the parent without an entry for s, but
      including the [:ask s]. This corresponds most closely to a subroutine call
      examining its context and the call stack.
    - version: Everything that led to that version.
    - top-level reflect: Everything that led to the reflect's workspace's
      current version plus that current version. If I wasn't able to reach the
      current version, I couldn't reflect a child immediately after a
      sub-question unlock returns, for example.
    - pointer to a copy of a top-level reflect: Everything before the first copy
      of that reflect was made.
    - :version/tx and :reflect/reachable must be monotonically decreasing on
      every branch of the reflection tree.
    - See also the rambling comments at the bottom of
      scenarios/07-reflect-ask-reply.edn.

- Putting a pointer at a reflection structure in a root answer.
    - Have to be careful not to automatically unlock any reflection entries.
    - The pointer itself can be unlocked, though, and will point to a
      `:reflection/*`, `:version/ws` or `:version/act` entry.
    - Pointers that cannot be unlocked automatically should be removed from the
      output.

- How to extract a pointer from a reflected workspace and unlock it in the
  current workspace?
    - Launder it through a sub-question.

- Navigating up:
    - Each workspace structure has a parent entry that the user can unlock and
      thus grow the tree from one level higher.
    - Except when the parent is a root question pseudo-workspace. In this case
      there should be no parent entry.
    - See also scenarios/06-reflect-root-earlier-reflect.edn.

- Reflecting workspaces that reflected other workspaces, and unlocking more of
  the other workspaces' reflection pointers:
    - See scenarios/05-reflect-reflect-reflect-3.edn.
    - Reflected workspaces' reflection entries cannot be unlocked.
    - They can be laundered through a sub-question.
    - All of a reflected workspace's reflection entries are also reachable
      without going through that reflected workspace, so they can just be
      unlocked there.

- Note that "r.3.children.0.4.ws.r.parent.2.act" looks unwieldy, but once you
  know how to read it, it's easy. Current ws → version 3 → child for
  sub-question 0 → version 4 → workspace → reflected parent → action in version
  2.
