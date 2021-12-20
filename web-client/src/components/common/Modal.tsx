import React, { ReactNode, useEffect } from "react";
import ReactDOM from "react-dom";
import './Modal.scss';

const Modal = (props: { children?: ReactNode, visible: boolean, onHide: () => void }) => {

  const modalRoot = document.getElementById('modal-root');
  const container = document.createElement("div")
  
  const hide = () => {
    props.onHide();
  }

  const modal = (
    <div
      key       = "my-modal"
      className = "my-modal-container"
      style     = { { display: "block" } }>

      <div 
        key       = "my-model-background"
        className = "my-modal-background"
        onClick   = { (e) => hide() }
      />

      <div key="my-model-content" className="my-modal-content">
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
