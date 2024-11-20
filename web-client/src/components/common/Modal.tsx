import React, { ReactNode, useEffect } from "react";
import './Modal.scss';

const Modal = (props: { children?: ReactNode, visible: boolean, onHide: () => void }) => {

  return(
    <div
      key       = "modal-container"
      style     = { { zIndex: props.visible ? 500 : -1, visibility: props.visible ? "visible" : "hidden" } }
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
  );
}

export default Modal
