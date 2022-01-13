import React, { ReactNode, useEffect } from "react";
import ReactDOM from "react-dom";
import './Modal.scss';

const Modal = (props: { children?: ReactNode, visible: boolean, onHide: () => void }) => {

  const modalRoot = document.getElementById('modal-root');
  const container = document.createElement("div")

  const modal = (
    <div
      key       = "modal-container"
      className = "modal-container">

      <div 
        key       = "model-background"
        className = "modal-background"
        onClick   = { () => props.onHide() }
      />

      <div 
        key       = "model-content" 
        className = "modal-content">
        { props.children }
      </div>
    </div>
  );

  useEffect(() => {
    if (props.visible && modalRoot) {
      modalRoot.appendChild(container)
      return () => { modalRoot.removeChild(container); }
    }
  }, [props])

  return(ReactDOM.createPortal(modal, container));
}

export default Modal
