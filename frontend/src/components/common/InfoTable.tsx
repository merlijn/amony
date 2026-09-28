import {ReactNode} from "react";

type InfoTableProps = {
  className?: string
  children: ReactNode
}

type InfoRowProps = {
  label: string
  children: ReactNode
}

/** A single label/value row. Renders as a row header plus its value cell. */
export const InfoRow = ({label, children}: InfoRowProps) => (
  <tr>
    <th scope="row" className="info-label">{label}</th>
    <td className="info-value">{children}</td>
  </tr>
)

/** Shared two-column table used to display information in dialogs. */
const InfoTable = ({className, children}: InfoTableProps) => (
  <table className={className ? `info-table ${className}` : "info-table"}>
    <tbody>{children}</tbody>
  </table>
)

export default InfoTable
