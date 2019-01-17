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

- Putting a pointer at at reflection structure in a root answer.
    - Have to be careful not to automatically unlock any reflection entries.
    - The pointer itself can be unlocked, though, and will point to a
      `:reflection/*`, `:version/ws` or `:version/act` entry.

- How to extract a pointer from a reflected workspace and unlock it in the
  current workspace?
    - Launder it through a sub-question.

- Upwards navigation:
    - Each workspace structure has a parent entry that the user can unlock and
      thus grow the tree from one level higher.

- Reflecting workspaces that reflected other workspaces, and unlocking more of
  the other workspaces' reflection pointers:
    - See scenarios/reflect-reflect-reflect-3.edn.
    - Reflected workspaces' reflection entries cannot be unlocked.
    - They can be laundered through a sub-question.
    - All of a reflected workspace's reflection entries are also reachable
      without going through that reflected workspace, they can just be unlocked
      there.
