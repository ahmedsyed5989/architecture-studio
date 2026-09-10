# Architect Studio for IntelliJ IDEA / WebStorm

Model UML diagrams directly in IntelliJ IDEA or WebStorm using the [Apollon](https://github.com/ls1intum/Apollon) editor. Diagrams are stored as `.apollon` files so you can version them in Git alongside your source.

> **Status: functional, not yet published.** This plugin reads and writes `.apollon` files, edits PlantUML class diagrams visually, follows the IDE theme, and exports SVG/PNG images. It isn't on the JetBrains Marketplace yet — see [`README.dev.md`](./README.dev.md) to build and run it from source.

## Install

Not yet published to the JetBrains Marketplace. See [`README.dev.md`](./README.dev.md) to build and run it from source.

## Features

- Open a `.apollon` file to edit its diagram on the canvas, or use **Tools → Architect Studio → New Diagram…** to create one.
- Edits sync to the underlying `Document` (undo, save, and version control all work normally); the canvas updates in turn when the file changes externally.
- The canvas follows the IDE's editor color scheme and Swing look-and-feel, live.
- **Tools → Architect Studio → Export Diagram…** renders the focused diagram to a sibling SVG or PNG; auto-export on save is configurable per-project under **Settings → Tools → Architect Studio**.
- The **Architect Studio** tool window lists every native `.apollon` file in the project for quick navigation.

## Edit a PlantUML class diagram visually

Right-click a `.puml`/`.plantuml`/`.pu`/`.wsd` file in the Project tool window and choose **Architect Studio → Edit**. The diagram opens on the same canvas as a native `.apollon` diagram — same elements, same properties, same controls.

- **Save** writes your canvas edits straight back to the `.puml` file. The `.puml` file is always the source of truth; the JSON Architect Studio uses internally to drive the canvas lives under a `.architect-studio/` directory it manages for you (added to `.gitignore` automatically) and is never something you open or edit directly.
- **Scope (v1): class diagrams only** — classes, interfaces, enums, abstract classes, attributes, methods, visibility, and the standard relationships (inheritance, realization, association, aggregation, composition, dependency), with multiplicities and role labels where PlantUML expresses them the same way Apollon does.
- **Nothing is silently discarded.** PlantUML syntax this version doesn't understand (notes, packages, aliases, non-class diagram types, …) is left exactly as it was in the file and reported rather than guessed at.
- If the `.puml` file changes outside Architect Studio while its canvas is open, you're warned before a save would overwrite that external change.

## Use

Supported diagram types match the [`@tumaet/apollon`](https://www.npmjs.com/package/@tumaet/apollon) library: class, object, activity, use case, communication, component, deployment, Petri net, reachability graph, syntax tree, flowchart, BPMN, and sequential function chart. PlantUML round-trip editing (above) currently covers class diagrams only.
