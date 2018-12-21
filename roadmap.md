Scenarios that Jursey should support next.


Pointers
--------

1. Ask root question that contains a pointer.
2. In root workspace, ask sub-question that passes the pointer on.
3. Unlock the sub-answer.
4. In sub-workspace, unlock the pointer.
5. Answer the question.
6. Back in root workspace, use sub-answer to give root answer.


Reflection
----------

1. Follow steps 1-5 from above.
2. Issue `:reflect`.
3. Navigate from the first version of the sub-workspace with locked pointer to
   the second version with unlocked pointer. Now the reflection shows the
   pointer unlocked in the reflected workspace, but locked in the root
   workspace.


Automation
----------

1. Ask root question that contains two pointers with different content.
2. In root workspace, ask two sub-questions with the same structure, but passing
   a different pointer to each.
3. Unlock the first sub-answer.
4. In sub-workspace, unlock the pointer.
5. Answer the question.
6. Back in root workspace, unlock the second sub-answer.
7. Automation kicks in and unlocks the pointer.
8. Answer the question.
