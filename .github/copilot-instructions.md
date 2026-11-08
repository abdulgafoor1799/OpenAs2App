# OpenAS2 / EDI Platform Guidelines

## System Role
OpenAS2 is the transport engine inside a larger EDI platform. Assume many suppliers and many trade partners, so design for n-to-n partner communication rather than a single retailer flow. RITHUM is one partner among many, not the center of the system.

## Priorities
Availability and reliability come first. Prefer safe, reversible changes, backward-compatible defaults, and partner-specific configuration over global changes.

## Configuration Boundaries
Most work should stay in partner-level and runtime config surfaces such as `openas2.properties`, `partnerships.xml`, AS2 keystores/certificates like `as2_certs.p12`, logging, and restart behavior. Avoid broad application refactors unless the configuration path cannot solve the problem.

## Docker Runtime
Keep the Dockerfile-based workflow working. The preferred local container shape is:
- container name `EDIPLUG_AS2_SERVER`
- ports `4080:10080` and `4081:10081`
- config mounted to `/opt/openas2/config`
- data mounted to `/opt/openas2/data`
- `OPENAS2_PROPERTIES_FILE=/opt/openas2/config/openas2.properties`
- `AS2_KEYSTORE_PASSWORD=abdul456`
- image tag `openas2:my-custom-tag`

## Working Style
Make the smallest change that solves the problem. When a requirement can be met by partner-specific configuration, choose that path before changing global behavior. Treat cert generation and rotation, logging, and restart procedures as operational work and preserve existing partner behavior.

## Validation
Use the repo's documented Docker flow in [README.md](../README.md) and the container setup in [Dockerfile](../Dockerfile) to verify changes before widening the scope.
