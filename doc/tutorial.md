# Tutorial

## Command overview

- `(set-up)` Connect to the database. You need to enter this after starting the
  REPL and before entering any other command.

- `(reset)` Delete the database and start over.

- `(ask-root …)` Ask a root question.

- `(get-root-qas)` Return all answered root questions and their answers.

- `(start-working …)` Find a blocking workspace, set it as the current workspace
  and return it. A workspace W is blocking if it is a root workspace without
  answer or if another workspace is waiting for W's answer.

- `(ask …)` Add a sub-question to the current workspace. Jursey is lazy, so
  it only presents this sub-question to a user after you unlock its answer.
  Return the next blocking workspace. This might be the current workspace with
  the added sub-question.

- `(unlock "<pointer path>")` Unlock the target of the pointer in the current
  workspace. If the target is an ungiven answer, make the current workspace wait
  until the answer has been given. Return the next blocking workspace. This
  might be the current workspace if the pointer's target was already present.

  Note that you cannot unlock a reflected pointer in place. What is reflected is
  supposed to be an unchanged snapshot of the past; you can't change it by
  unlocking something. If you still want to know what is behind the pointer, you
  can launder it. See the [feature demo](feature_demo.clj), section
  ‘Unlocking reflected pointers’.

- `(reply …)` Answer the current workspace's question. Unblock all workspaces
  and root questions that are waiting for the answer. Return the next blocking
  workspace or `nil` if there are none.

- Jursey doesn't have a `(scratch …)` command, because you can abuse
  sub-questions as scratchpads. Just make sure not to unlock their answers.

## Preconditions

- You know about [capability
  amplification](https://ought.org/projects/factored-cognition/taxonomy) and
  ideally you have used programs similar to Jursey before. Examples are
  [Mosaic](https://github.com/oughtinc/mosaic),
  [Affable](https://github.com/oughtinc/affable/) and
  [Patchwork](https://github.com/oughtinc/patchwork).

- You have set up Jursey according to the [README](/README.md) and now see a
  REPL prompt:

    ```
    jursey.repl-ui=>
    ```


## Interlude with Slime

The most convenient way to work with a REPL is to write your commands in an
editor and send them to the REPL with a keyboard shortcut. You can do that with
[IntelliJ IDEA + Cursive](https://cursive-ide.com/), [Vim +
Tmux](https://github.com/jpalardy/vim-slime), [Vim +
iTerm2](https://github.com/matschaffer/vim-islime2), and [Emacs +
Cider](https://github.com/clojure-emacs/cider).


## Warning to the unwary visitor

Jursey is not (yet?) user-friendly. If your input doesn't follow the rules, the
output won't make sense to you. The error messages won't be helpful.


## Ready, set, PENG

<!-- TODO: Automatically extract a REPL file from this and run it to make sure
that all the commands and results are correct. (RM 2019-02-11) -->

Connect to the database:

```
jursey.repl-ui=> (set-up)
```

From now on I will leave out the prompt.

Ask a root question:

```
(ask-root "What is the capital of [Texas]?")
```

After you ask a root question, you have to tell Jursey that you want to work on
it:

```
(start-working)
{"q" "What is the capital of $1?", "r" :locked, "sq" {}}
```

It shows you a workspace for the root question. This contains the question with
the key `q`, a reflection entry with the key `r` and an empty list of
sub-questions `sq`. `$1` is a locked pointer. Let's ask a sub-question, passing
on this pointer:

```
(ask "What is the capital city of $q.1?")
{"q" "What is the capital of $1?",
 "r" :locked,
 "sq" {"0" {"a" :locked, "q" "What is the capital city of $q.1?"}}}
```

You see that the pointer name `1` is local to the question. When you pass it on,
you have to say: ‘pointer `1` in entry `q`’. The new workspace contains the
sub-question you just asked. You can ignore the fact that the `a` entry comes
before the `q` entry – the data structures you see are arbitrarily ordered.

We expect that the answer to the sub-question will already be the answer to the
root question. So let's just reply with it:

```
(reply "It should be $sq.0.a.")
; ‘Reply with the answer (a) of subquestion (sq) no. 0.’
{"q" "What is the capital city of $1?", "r" :locked, "sq" {}}
```

Now what is this workspace? When Jursey shows a root question answer A to a
user, all the pointers in A have to be unlocked. When you reply `It should be
$sq.0.a.` to the root question, Jursey tries to unlock the pointer, but fails,
because the target is an ungiven answer. So it presents you with a workspace
where you can give the answer.

In order to give an answer, we have to see what is hidden behind the pointer.
Note that you write only the pointer path, not the `$`.

```
(unlock "q.1")
{"q" "What is the capital city of [0: Texas]?", "r" :locked, "sq" {}}

(reply "Austin")
nil
```

The return value `nil` indicates that there are no more workspaces to work on.
Let's check if there is a root answer.

```
(get-root-qas)
(["What is the capital of [1: Texas]?" "It should be [1: Austin]."])
```

Done.
