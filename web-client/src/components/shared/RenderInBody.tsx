// var RenderInBody = React.createClass({

import React, { ReactElement } from "react"
import { ReactNode, useEffect } from "react"
import { propTypes } from "react-bootstrap/esm/Image"
import ReactDOM from "react-dom"

const RenderInBody = (props: { children: ReactElement }) => {

  useEffect(() => {
    const div = document.createElement("div")
    document.body.appendChild(div)
    ReactDOM.render(props.children, div)
  })

  // return placeholder
  return <div />
}

export default RenderInBody