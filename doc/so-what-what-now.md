So what? – What do we learn from Jursey and its implementation of reflection?
-----------------------------------------------------------------------------

- One can implement reflection in less than a person-month within the Patchwork
  model. (Ie., workspaces with question and sub-questions, and their answers.)

- The basic (but complete) form of reflection that I implemented [doesn't play
  well](/scenarios/08-failed-automation-diff.repl) with automation. It should be
  possible to [repair this](/doc/design/002-reflection-automation.md), but it
  will make the implementation and the UI more complicated.

- Datomic makes it easy to go back in time. With a conventional database one
  would have to develop one's own mechanisms for that.

- Another advantage of Datomic is its notion of a database as an immutable
  snapshot at a point in time. Because it's immutable, one can pass it to pure
  functions.

- Copying hypertext instead of sharing works well.

- Contexts don't work well.

- Local pointer names/pointer paths work well.


What now? – How could we continue from here?
--------------------------------------------

Options, including my perspective (MP):

1. Validate and implement the [design for automation-friendly
   reflection](/doc/design/002-reflection-automation.md). To do:

    - TODOs in DR 2.
    - Validate the more arcane cases.
    - Write another version of reflect³ according to the latest design.
    - Validate the design (and reflection-without-nesting) on another sketch of
      diffing.
    - Revise.
    - Implement.

   MP: Much work for an insignificant feature.

2. Add features to Jursey. Ought's [Progress Update Winter
   2018](https://ought.org/blog/2018-12-31-progress-update) mentions
   *speculative execution* and *scheduling via question-answering*. *Budgets*,
   *edits* and *parallel unlocks* also come to mind. I haven't thought these
   through, but if I can figure out reflection, I can also figure out other
   things.

   MP: Probably the place where I could contribute most.

3. Build reflection into Affable. This would fit with the the goal to
   consolidate all the systems for factored cognition into one (cf. the Progress
   Update). Also, Derek mentioned that he could use help with Affable.

   MP: Might not be effective, because my Haskell skills need much polishing.

4. Set up a client-server system with automation as a special kind of client.
   This was my plan in the beginning. But since Mosaic and Patchwork already
   have client-server capabilities, I feel that I could only contribute a small
   amount of novelty.

   MP: Unimportant, but good for gaining experience in web technology and
   distributed systems.

5. Stop. I don't know how useful my external solo work is, so not continuing is
   always possible.

   MP: There is no clear ‘yes, let's do this’ option among the above, so it
   might make sense to move on to other adventures.
