import React, { ReactNode } from "react";
import './Modal.scss';

const Modal = (props: { children?: ReactNode, onHide: () => void }) => {

  return (
    <div
      key="modal"
      className="my-modal-container"
      style={ { display: "block" }}>

      <div key="my-model-background"
           className="my-modal-background"
           onClick = { (e) => props.onHide() }
      />

      <div key="my-model-content" className="my-modal-content">
        { props.children }
      </div>
    </div>
  );
}

export default Modal
