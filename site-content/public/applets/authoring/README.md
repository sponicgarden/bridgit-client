# Offline applet authoring kit

Download [the authoring kit](bridgit-applet-authoring-v1.zip), unzip it and
read `README.md`. Validate with `java -jar validate-applet.jar applet.json`
on Java 17 or newer. The archive includes a packer, references and examples.

The following references can be read offline. The runtime exports the field and service inventories. Its workflow validator checks the trigger examples.

- `workflow-step.schema.json`: typed inputs, numeric bounds, reference shapes and retry conditions for all 21 step types. Use the bundled validator for grants, condition expressions and cross-step references.
- `workflow-trigger.schema.json`: authoring schema and examples for the 11 trigger types and the legacy application-event alias.
- `flow-contract.json`: accepted step names, field inventory, scopes and common limits.
- `services.json`: named services, selection examples and budget/configuration interfaces.
  Availability is deliberately `runtime_check_required`, not a live status snapshot.

These inventories are not yet the complete package schema or complete flow reference.
The remaining contract, portable-runtime integration and release acceptance work is
recorded in `docs/applet-package-contract.md` in the app development repository.
A package that passes current static checks still needs device capabilities,
owner grants, configured service bindings, and runtime testing.
