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

Options:

1. Set up a server-client system with automation as a special kind of client.
   This was my plan in the beginning. But since Mosaic and Patchwork already
   have server-client capabilities, I feel that I could only contribute a small
   amount of novelty. Note that after a few hours of thinking about the design,
   I guess that I wouldn't be able to finish implementation by the end of
   February.

2. Add features to Jursey. Ought's [Progress Update Winter
   2018](https://ought.org/blog/2018-12-31-progress-update) mentions
   *speculative execution* and *scheduling via question-answering*. *Budgets*,
   *edits* and *parallel unlocks* also come to mind. I haven't thought these
   through, but if I can figure out reflection, I can also figure out other
   things.

3. Build reflection into Affable. This would fit with the the goal to
   consolidate all the systems for factored cognition into one (cf. the Progress
   Update). However, it might not be effective for Derek to handhold me while
   I'm stumbling around with Haskell.

4. Don't continue. I don't know how useful my external solo work is, so not
   continuing is always an option.

Recommendation from my perspective: I think I could contribute most by working
on some of the features in no. 2. Since there is no clear ‘yes, let's do this’
option, it might also make sense to move on to other adventures (option 4).
Option 1 would be fun, but there appears to be little need for it.
