So what? – What do we learn from Jursey and its implementation of reflection?
-----------------------------------------------------------------------------

I have learned a lot about my work process from developing Jursey. My
conclusions for research are comparatively weak – maybe you can come up with
more:

- One can implement reflection in less than a person-month within the Patchwork
  model. (Ie., workspaces with question and sub-questions, and their answers.)

- Datomic makes it easy to go back in time. With a conventional database one
  would have to develop ones own mechanisms for that.

- Another advantage of Datomic is its notion of a database as an immutable
  snapshot at a point in time. Because it's immutable, one can pass it to pure
  functions.

- Copying hypertext instead of sharing works well.

- Contexts don't work well.

- Local pointer names/pointer paths work well.


What now? – How could we continue from here?
--------------------------------------------
