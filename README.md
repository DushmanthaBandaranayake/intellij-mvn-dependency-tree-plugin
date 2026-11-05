# Maven Dependency Tree Graph

A free IntelliJ IDEA plugin that shows a Maven project's dependencies as an interactive, collapsible graph. Right-click a `pom.xml` to see the full tree, spot version conflicts, and jump straight to where a dependency is declared.

![Opening the dependency tree from a pom.xml](docs/images/01-open-dependency-tree.gif)

> The animations in this README are illustrations of the workflow, not recordings. The real IDE window will look a little different.

## Features

- **Graphical tree:** see direct and transitive dependencies as a left-to-right graph, with one tab per POM.
- **Expand and collapse:** open branches one at a time, or expand and collapse the whole tree in one click.
- **Search:** highlight every matching dependency, open the branches they're in, and see how many matched.
- **Jump to the POM:** click a node to open the place it's declared.
- **Colour coding:** conflicts, duplicates and omitted dependencies, test scope, and provided or runtime scope are all marked.
- **Pan and zoom:** drag to move around and use the mouse wheel to zoom.
- **Light and dark themes:** the graph follows your IDE theme.

## Requirements

- IntelliJ IDEA 2021.3 or newer, with the bundled Maven plugin enabled
- A `pom.xml` imported as a Maven project

## Usage

### Open the tree

![Expanding and collapsing the tree](docs/images/02-expand-and-collapse.gif)

### Search

![Searching the tree](docs/images/03-search-and-highlight.gif)

### Jump to the POM
![Jumping from a node to its declaration in the POM](docs/images/04-jump-to-pom.gif)

## License

Free to use under the [MIT License](LICENSE). Copyright (c) 2026 Dushmantha Bandaranayake.
