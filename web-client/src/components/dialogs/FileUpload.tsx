import React, { ChangeEvent, useState } from 'react';
import { Api } from '../../api/Api';
import { dateMillisToString } from '../../api/Util';
 
const FileUpload = () => {
  
    const [file, setFile] = useState<File | undefined>(undefined)
    const [feedback, setFeedback] = useState<string | undefined>(undefined)

    // On file select (from the pop up)
    const onFileChange = (event: ChangeEvent<HTMLInputElement>) => {
    
      if (event.target.files) {
        setFile(event.target.files[0]);
      }    
    };
    
    // On file upload (click the upload button)
    const onFileUpload = () => {
    
      if (file) {
        Api.uploadFile(file).then(() => {
          setFeedback("Done!")
          setFile(undefined)
        });
      }
    };
    
    return (
      <div className="modal-dialog">
          <h3>File Upload</h3>
          <div>
              <input type="file" accept=".mp4" onChange = { onFileChange} />
              <button className="button-primary" onClick = { onFileUpload} >Upload</button>
          </div>
          { feedback && <div><p>{ feedback }</p></div>}
          {
            file && 
              <div>
                <h2>File Details:</h2>
                <p>File Name: {file.name}</p>
                <p>File Type: {file.type}</p>
                <p>Last Modified: { dateMillisToString(file.lastModified) }</p>
              </div>
          }
      </div>
    );
  }
 
export default FileUpload;