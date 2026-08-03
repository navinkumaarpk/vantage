/**
 * Diagnosis canvas — ONU onu0301046 vOMCI "onu not found" failure
 * 2026-07-27T13:36:58–13:36:59 UTC
 */
import {
  Callout,
  Card,
  CardBody,
  CardHeader,
  Divider,
  Grid,
  H1,
  H2,
  H3,
  Pill,
  Row,
  Stack,
  Stat,
  Table,
  Text,
  useHostTheme,
} from 'cursor/canvas';

/* ------------------------------------------------------------------ data */

const ONU = {
  id: 'onu0301046',
  serial: 'CIGG21B00EFF',
  uuid: 'ONU-a5ffe0bd-c0bf-493c-bba4-ceb68b96f5da',
  vcmMac: '520000000001',
  rolt: 'ROLT-47',
  elementId: 'e0e4cf9f-8480-442c-87ee-dbd2bff65707',
  pon: '2/1/46',
  timestamp: '2026-07-27T13:36:58–59 UTC',
  thread: 'ROLT Manager ONT Queue-5',
};

const VOMCI_ERRORS = [
  {
    endpoint: 'GET /vomci/api/v1.0/onu/onu0301046',
    purpose: 'Element bindings',
    result: '"onu not found"',
  },
  {
    endpoint: 'POST /vomci/api/v1.0/onu/onu0301046/statprobe',
    purpose: 'Stats probe',
    result: '{"error":"ONU \'onu0301046\' not found"}',
  },
  {
    endpoint: 'GET /vomci/api/v1.0/mibtree/onu0301046/leaf/circuitpack/0x180',
    purpose: 'ANI attributes',
    result: '"onu not found"',
  },
];

type MsgKind = 'request' | 'response' | 'error' | 'note';

interface SeqMessage {
  from: number;
  to: number;
  label: string;
  kind: MsgKind;
}

const PARTICIPANTS = ['ROLT-47', 'OltMgr', 'vOMCI', 'VCM'];

const MESSAGES: SeqMessage[] = [
  { from: 0, to: 1, label: 'ONT_ADDED (CIGG21B00EFF → onu0301046)', kind: 'request' },
  { from: 1, to: 2, label: 'GET /onu/onu0301046', kind: 'request' },
  { from: 2, to: 1, label: '400 onu not found', kind: 'error' },
  { from: 1, to: 2, label: 'POST /onu/onu0301046/statprobe', kind: 'request' },
  { from: 2, to: 1, label: '400 ONU not found', kind: 'error' },
  { from: 1, to: 2, label: 'GET /mibtree/onu0301046/.../0x180', kind: 'request' },
  { from: 2, to: 1, label: '400 onu not found', kind: 'error' },
  { from: 1, to: 1, label: 'Errors logged; flow continues', kind: 'note' },
  { from: 1, to: 1, label: 'REGISTER_EXISTING_ONU (skip OnuAddedTrap)', kind: 'note' },
  { from: 1, to: 3, label: 'VCM 520000000001 registration', kind: 'request' },
  { from: 3, to: 1, label: '→ operational (13:37:03)', kind: 'response' },
];

/* -------------------------------------------------------- sequence diagram */

const DIAGRAM = {
  padX: 24,
  padTop: 48,
  padBottom: 24,
  colW: 160,
  rowH: 44,
  boxH: 32,
  boxW: 112,
  arrowHead: 7,
};

function strokeFor(kind: MsgKind, theme: ReturnType<typeof useHostTheme>): string {
  switch (kind) {
    case 'error':
      return '#cf2d56';
    case 'response':
      return '#1f8a65';
    case 'note':
      return theme.stroke.tertiary;
    default:
      return theme.accent.primary;
  }
}

function SequenceDiagram(): JSX.Element {
  const theme = useHostTheme();
  const n = PARTICIPANTS.length;
  const width = DIAGRAM.padX * 2 + DIAGRAM.colW * (n - 1) + DIAGRAM.boxW;
  const height =
    DIAGRAM.padTop + MESSAGES.length * DIAGRAM.rowH + DIAGRAM.padBottom;

  const colX = (i: number) =>
    DIAGRAM.padX + i * DIAGRAM.colW + DIAGRAM.boxW / 2;

  return (
    <svg
      width="100%"
      viewBox={`0 0 ${width} ${height}`}
      role="img"
      aria-label="Sequence diagram for ONU onu0301046 vOMCI failure"
      style={{ display: 'block', maxWidth: width }}
    >
      {/* lifelines */}
      {PARTICIPANTS.map((_, i) => {
        const x = colX(i);
        return (
          <line
            key={`life-${i}`}
            x1={x}
            y1={DIAGRAM.padTop}
            x2={x}
            y2={height - DIAGRAM.padBottom}
            stroke={theme.stroke.tertiary}
            strokeWidth={1}
            strokeDasharray="4 4"
          />
        );
      })}

      {/* participant boxes */}
      {PARTICIPANTS.map((name, i) => {
        const x = colX(i) - DIAGRAM.boxW / 2;
        const y = 12;
        return (
          <g key={`box-${name}`}>
            <rect
              x={x}
              y={y}
              width={DIAGRAM.boxW}
              height={DIAGRAM.boxH}
              rx={6}
              fill={theme.fill.secondary}
              stroke={theme.stroke.secondary}
              strokeWidth={1}
            />
            <text
              x={colX(i)}
              y={y + DIAGRAM.boxH / 2 + 4}
              textAnchor="middle"
              fill={theme.text.primary}
              fontSize={12}
              fontWeight={600}
            >
              {name}
            </text>
          </g>
        );
      })}

      {/* messages */}
      {MESSAGES.map((msg, idx) => {
        const y = DIAGRAM.padTop + idx * DIAGRAM.rowH + DIAGRAM.rowH / 2;
        const x1 = colX(msg.from);
        const x2 = colX(msg.to);
        const color = strokeFor(msg.kind, theme);
        const self = msg.from === msg.to;
        const labelX = self ? x1 + 10 : (x1 + x2) / 2;
        const labelY = y - 6;

        if (self) {
          const loopW = 52;
          const loopH = 22;
          return (
            <g key={`msg-${idx}`}>
              <path
                d={`M ${x1} ${y} h ${loopW} v ${loopH} h ${-loopW}`}
                fill="none"
                stroke={color}
                strokeWidth={1.5}
              />
              <text
                x={x1 + loopW / 2}
                y={y - 4}
                textAnchor="middle"
                fill={theme.text.secondary}
                fontSize={10}
              >
                {msg.label}
              </text>
            </g>
          );
        }

        const leftToRight = x2 > x1;
        const ax = leftToRight ? x1 : x2;
        const bx = leftToRight ? x2 : x1;
        const markerEnd = leftToRight ? 'url(#arrow)' : 'url(#arrow-left)';

        return (
          <g key={`msg-${idx}`}>
            <line
              x1={ax}
              y1={y}
              x2={bx}
              y2={y}
              stroke={color}
              strokeWidth={msg.kind === 'error' ? 2 : 1.5}
              markerEnd={markerEnd}
            />
            <text
              x={labelX}
              y={labelY}
              textAnchor="middle"
              fill={msg.kind === 'error' ? '#cf2d56' : theme.text.secondary}
              fontSize={10}
              fontWeight={msg.kind === 'error' ? 600 : 400}
            >
              {msg.label.length > 42 ? msg.label.slice(0, 41) + '…' : msg.label}
            </text>
          </g>
        );
      })}

      <defs>
        <marker
          id="arrow"
          markerWidth={DIAGRAM.arrowHead}
          markerHeight={DIAGRAM.arrowHead}
          refX={DIAGRAM.arrowHead - 1}
          refY={DIAGRAM.arrowHead / 2}
          orient="auto"
        >
          <polygon
            points={`0 0, ${DIAGRAM.arrowHead} ${DIAGRAM.arrowHead / 2}, 0 ${DIAGRAM.arrowHead}`}
            fill={theme.accent.primary}
          />
        </marker>
        <marker
          id="arrow-left"
          markerWidth={DIAGRAM.arrowHead}
          markerHeight={DIAGRAM.arrowHead}
          refX={1}
          refY={DIAGRAM.arrowHead / 2}
          orient="auto"
        >
          <polygon
            points={`${DIAGRAM.arrowHead} 0, 0 ${DIAGRAM.arrowHead / 2}, ${DIAGRAM.arrowHead} ${DIAGRAM.arrowHead}`}
            fill="#cf2d56"
          />
        </marker>
      </defs>
    </svg>
  );
}

/* --------------------------------------------------------------------- main */

export default function Onu0301046DiagnosisCanvas(): JSX.Element {
  return (
    <Stack gap={20}>
      <Row gap={12} align="center">
        <H1>ONU onu0301046 — Diagnosis</H1>
        <Pill tone="warning">vOMCI sync failure</Pill>
        <Pill tone="neutral">{ONU.timestamp}</Pill>
      </Row>

      <Text tone="secondary">
        OltMgr received an ONT_ADDED event and immediately queried vOMCI to populate
        the ONU model. vOMCI returned HTTP 400 &quot;onu not found&quot; on every call.
        Processing continued as an existing-ONU re-registration; the VCM still reached
        operational state.
      </Text>

      <Grid columns={4} gap={12}>
        <Stat value={ONU.serial} label="Serial" />
        <Stat value={ONU.vcmMac} label="VCM MAC" tone="info" />
        <Stat value={ONU.pon} label="PON position" />
        <Stat value="3" label="vOMCI errors" tone="warning" />
      </Grid>

      <Card>
        <CardHeader trailing={<Pill tone="info">sequence</Pill>}>
          <H2>Failure sequence</H2>
        </CardHeader>
        <CardBody>
          <SequenceDiagram />
        </CardBody>
      </Card>

      <Grid columns={2} gap={16}>
        <Card>
          <CardHeader>
            <H3>Device identifiers</H3>
          </CardHeader>
          <CardBody>
            <Stack gap={8}>
              <Text size="small">
                <Text as="span" weight="semibold">
                  ONU ID:{' '}
                </Text>
                {ONU.id}
              </Text>
              <Text size="small">
                <Text as="span" weight="semibold">
                  UUID:{' '}
                </Text>
                {ONU.uuid}
              </Text>
              <Text size="small">
                <Text as="span" weight="semibold">
                  ROLT:{' '}
                </Text>
                {ONU.rolt} ({ONU.elementId})
              </Text>
              <Text size="small">
                <Text as="span" weight="semibold">
                  Worker thread:{' '}
                </Text>
                {ONU.thread}
              </Text>
            </Stack>
          </CardBody>
        </Card>

        <Card>
          <CardHeader>
            <H3>Code path</H3>
          </CardHeader>
          <CardBody>
            <Stack gap={6}>
              <Text size="small">ROLTManager.handleOntAdded (812)</Text>
              <Text size="small" tone="tertiary">
                ↳ PopulatorService.doPopulate (580–590)
              </Text>
              <Text size="small" tone="tertiary">
                ↳ getElementBindings / getStatsFromProbe / getAniOperStatus
              </Text>
              <Text size="small" tone="tertiary">
                ↳ HttpPatchClientApache → vOMCI @ 10.184.174.47:9000
              </Text>
            </Stack>
          </CardBody>
        </Card>
      </Grid>

      <Card>
        <CardHeader trailing={<Pill tone="danger">HTTP 400</Pill>}>
          <H2>vOMCI REST failures</H2>
        </CardHeader>
        <CardBody>
          <Table
            headers={['Endpoint', 'Purpose', 'Response']}
            rows={VOMCI_ERRORS.map((e) => [e.endpoint, e.purpose, e.result])}
            rowTone={['danger', 'danger', 'danger']}
          />
        </CardBody>
      </Card>

      <Callout tone="warning" title="Root cause">
        vOMCI had no record of onu0301046 when OltMgr queried it during ONT_ADDED
        handling. OltMgr treated this as an existing ONU re-registration
        (REGISTER_EXISTING_ONU), but vOMCI state was out of sync. Populate errors are
        non-fatal — the flow continued and VCM 520000000001 reached operational by
        13:37:03 without full vOMCI data.
      </Callout>

      <Callout tone="info" title="Likely explanations">
        Stale vOMCI state after ONU removal; a timing race where ONT_ADDED fired before
        vOMCI created the ONU object; or a re-add after outage where OltMgr re-registered
        from cached state before vOMCI synced.
      </Callout>

      <Divider />

      <Text tone="tertiary" size="small">
        Secondary: ServiceClass config errors for VCM 520000000001 (missing onu-mgmt,
        data-200, voice) — did not block registration. Check vOMCI logs at
        10.184.174.47:9000 around 13:36:58 UTC.
      </Text>
    </Stack>
  );
}
