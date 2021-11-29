import React, { ReactNode } from "react";
import './Modal.scss';

const Modal = (props: { children?: ReactNode, onHide: () => void }) => {

  return (
    <div
      key="modal"
      className="modal-container"
      style={ { display: "block" }}>

      <div key="model-background"
           className="modal-background"
           onClick = { (e) => props.onHide() }
      />

      <div key="model-content" className="modal-content">
        { props.children }
      </div>
    </div>
  );
}

export default Modal
