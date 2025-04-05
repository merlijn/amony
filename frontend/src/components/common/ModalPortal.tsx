import React, { ReactNode, useEffect } from "react";
import ReactDOM from "react-dom";
import './Modal.scss';

const ModalPortal = (props: { children?: ReactNode, visible: boolean, onHide: () => void }) => {

  const modalRoot = document.getElementById('modal-root');
  const container = document.createElement("div")
  const zIndex = props.visible ? 500 : -1

  const modal =
    <div
      key       = "modal-container"
      style     = { { zIndex: zIndex, visibility: props.visible ? "visible" : "hidden" } }
      className = "modal-container">

      <div
        key       = "modal-background"
        className = "modal-background"
        onClick   = { () => props.onHide() }
      />

      <div
        key       = "model-content"
        className = "modal-content">
        { props.children }
      </div>
    </div>

  useEffect(() => {
    if (props.visible && modalRoot) {
      modalRoot.appendChild(container)
      return () => { modalRoot.removeChild(container); }
    }
  }, [props])

  return(ReactDOM.createPortal(modal, container));
}

export default ModalPortal
