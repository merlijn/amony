import React, { ReactNode, useEffect } from "react";
import ReactDOM from "react-dom";
import './Modal.scss';

const Modal = (props: { children?: ReactNode, visible: boolean, onHide: () => void }) => {

  // add an element to the root of the document
  const container = document.createElement("div")
  
  const hide = () => {
    props.onHide();
  }

  const modal = (
    <div
      key="modal"
      className="my-modal-container"
      style={ { display: "block" }}>

      <div key="my-model-background"
           className="my-modal-background"
           onClick = { (e) => hide() }
      />

      <div key="my-model-content" className="my-modal-content">
        { props.children }
      </div>
    </div>
  );
  
  useEffect(() => {
    if (props.visible) {
      document.body.appendChild(container)
      ReactDOM.render(modal, container)
      return () => { document.body.removeChild(container); }
    }
  }, [props])

  return <div />
}

export default Modal
