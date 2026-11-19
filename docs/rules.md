# Rules for Changes

These rules govern how changes to this repository are scoped, tracked and reviewed. They apply to everyone, people and coding agents alike. The design philosophy that shapes *what* the code looks like is in [architecture.md](architecture.md#design-philosophy); these rules are about *how* changes land.

## 1. A project-wide change gets its own tracking issue

A change that reaches across the whole project, rather than one feature, is tracked by an issue of its own and lands in its own pull request. Don't fold it into a feature or bug-fix PR, even when the feature work is what made you notice it.

Project-wide means the change touches many packages or files for one cross-cutting reason, for example:

- adopting nullability annotations everywhere ([#13](https://github.com/cockroachlabs/cockroachdb-spring-boot-starter/issues/13));
- narrowing visibility across the starter ([#16](https://github.com/cockroachlabs/cockroachdb-spring-boot-starter/issues/16));
- upgrading a major dependency, or changing the build or CI setup;
- renaming or moving packages.

When feature work turns up something project-wide, open the tracking issue, note there what you found (ideally with the patch), and keep the feature PR to the feature. Applying a rule to the code a PR adds or changes is fine; applying it to the rest of the codebase is the separate issue.

This keeps reviews focused: a reviewer can judge a feature on its own, and a project-wide change on its own, instead of picking one out of the other.

## 2. Reformatting is never mixed with functional changes

Reformatting the codebase, or making widespread formatting or style corrections (indentation, import order, brace style, line wrapping, whitespace), is its own change, in its own commit and pull request, with no functional modifications in it. A functional change, in turn, doesn't carry formatting corrections beyond the lines it already touches.

Mixed in with formatting noise, a behavior change is easy to miss in review and hard to find later in `git blame` or when bisecting. Kept apart, a formatting-only change can be reviewed quickly ("nothing but formatting") and a functional change shows only what it does.

Code that a functional change adds or rewrites should follow the project's style; that is not reformatting.

## 3. One approval from the other maintainer is enough to proceed

The project has two maintainers, Greg Turnquist (GitHub `gregturn`) and Felipe Gutierrez (GitHub `felipeg48`). A pull request opened by one of them is approved to move forward, and can be merged, once the other has approved it on GitHub. No further sign-off is needed.

A maintainer doesn't approve their own pull request. Changes requested in review are addressed before merging, and CI must pass.
