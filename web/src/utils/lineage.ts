import type { LineageEdge, LineageNode, LineageProjection } from '@/types/trace'
import { formatLineageNodeLabel } from './formatters'

/**
 * 消费者公开谱系的展示辅助：只基于服务端返回的公开节点与边（响应内局部键），不推导任何未返回的批次。
 */

export interface LineageRowNode extends LineageNode {
  label: string
}

export interface LineageRow {
  generation: number
  nodes: LineageRowNode[]
  /** 进入本行节点的物料转换（合并时多条同类边只展示一次） */
  incoming: Array<Pick<LineageEdge, 'operationType' | 'occurredAt'>>
}

function derivedBy(nodeKey: string, edges: LineageEdge[]): string | null {
  return edges.find((e) => e.toNodeKey === nodeKey)?.operationType ?? null
}

/** 节点键 → 展示标签（来源批次 / 加工批次 / 拆分批次 / 本批次 …）。 */
export function lineageNodeLabels(lineage: LineageProjection | null | undefined): Record<string, string> {
  const labels: Record<string, string> = {}
  if (!lineage) return labels
  for (const node of lineage.nodes) {
    labels[node.nodeKey] = formatLineageNodeLabel(node.role, derivedBy(node.nodeKey, lineage.edges))
  }
  return labels
}

/** 按世代分行（服务端已按世代与稳定次序排列节点，这里保持该顺序）。 */
export function lineageRows(lineage: LineageProjection | null | undefined): LineageRow[] {
  if (!lineage) return []
  const labels = lineageNodeLabels(lineage)
  const rows = new Map<number, LineageRow>()
  for (const node of lineage.nodes) {
    let row = rows.get(node.generation)
    if (!row) {
      row = { generation: node.generation, nodes: [], incoming: [] }
      rows.set(node.generation, row)
    }
    row.nodes.push({ ...node, label: labels[node.nodeKey] })
    for (const edge of lineage.edges) {
      if (edge.toNodeKey !== node.nodeKey) continue
      if (!row.incoming.some((i) => i.operationType === edge.operationType && i.occurredAt === edge.occurredAt)) {
        row.incoming.push({ operationType: edge.operationType, occurredAt: edge.occurredAt })
      }
    }
  }
  return [...rows.values()].sort((a, b) => a.generation - b.generation)
}
