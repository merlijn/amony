import React, {useEffect} from "react";
import './ConfigMenu.scss';
import Form from "react-bootstrap/Form";
import {calculateColumns, useCookiePrefs} from "../api/Util";
import DropDownIcon from "./shared/DropDownIcon";
import * as config from "../AppConfig.json";
import {Constants} from "../api/Constants";
import {Prefs} from "../api/Model";

const ConfigMenu = () => {

  const [prefs, setPrefs] = useCookiePrefs<Prefs>("prefs", "/", Constants.defaultPreferences)

  const columns = [1, 2, 3, 4, 5, 6, 7].map((v) => {
    return { value: v, label: v.toString() }
  })

  const updatePrefs = (values: {}) => { setPrefs({...prefs, ...values} ) }

  return(

    <DropDownIcon iconSrc="/icons/tune.svg"
                  alignRight={true}
                  buttonClassName="mr-sm-1 config-menu-button"
                  contentClassName="config-menu">

      <>

      <div key="filter-title" className="config-title">Search</div>
      <div key="filter-form" className="config-form">

        <div className="form-section">
          <div className="form-label">Sort by</div>
          <div className="form-content">
            <select style={ { float: "left" } } className="mr-2" name="sort-field" onChange={(e) => { updatePrefs( { sortField: e.target.value }) } }>
              {
                Constants.sortOptions.map((v) => {
                  return <option
                    selected={ prefs.sortField === v.value }
                    key={`sorting-${v.value}`}
                    value={v.value}
                    label={v.label}
                  />;
                })
              }
            </select>
            <Form.Check
              label="reverse"
              style={ { float: "left" } }
              type="checkbox"
              checked={ prefs.sortDirection === "desc" }
              onChange={(e) => {
                updatePrefs( { sortDirection: (prefs.sortDirection === "asc") ? "desc" : "asc" })
              }
              }
            />
          </div>
        </div>

        <div className="form-section">
          <div className="form-label">Video quality</div>
          <div className="form-content">
            {
              Constants.resolutions.map((v) => {
                return <Form.Check
                  style={ { float:"left" } }
                  className="mr-1"
                  name="resolution"
                  type="radio"
                  key={`resolution-${v.value}`}
                  value={v.value}
                  label={v.label}
                  checked={prefs.minRes === v.value}
                  onChange={(e) => { updatePrefs( { minRes: v.value }) } }
                />;
              })
            }
          </div>
        </div>

      </div>

      <div key="config-title" className="config-title">Preferences</div>

      <div key="config-form" className="config-form">

        <div className="form-section">
          <p className="form-label">Number of columns</p>
          <div className="form-content">
            <Form.Check
              style={ { float: "left" } }
              className="mr-1"
              name="ncols-option"
              type="radio"
              value={0}
              label={"auto"}
              checked={prefs.gallery_columns === 0}
              onChange={(e) => {
                if (prefs.gallery_columns > 0) {
                  updatePrefs({gallery_columns: 0 })
                }
              }}
            />
            <Form.Check
              style={ { float: "left" } }
              className="mr-1"
              name="ncols-option"
              type="radio"
              value={0}
              label={"other"}
              checked={prefs.gallery_columns > 0}
              onChange={(e) => {
                if (prefs.gallery_columns === 0)
                  updatePrefs({ gallery_columns: calculateColumns()} )
              }}
            />
            <select name="ncols" onChange={(e) => { updatePrefs( { gallery_columns: e.target.value }) } }>
              {
                columns.map((v) => {
                  return <option
                    selected={ (prefs.gallery_columns === 0 && v.value === calculateColumns()) || prefs.gallery_columns === v.value }
                    value={v.value}
                    label={v.label}
                  />;
                })
              }
            </select>
          </div>
        </div>

        <div className="form-section">
          <p className="form-label">Show info bar</p>
          <div className="form-content">
            <Form.Check
              type="checkbox"
              checked={ prefs.showTitles }
              onChange={(e) => { updatePrefs( { showTitles: !prefs.showTitles }) } }
            />
          </div>
        </div>

        <div className="form-section">
          <p className="form-label">Show video duration</p>
          <div className="form-content">
            <Form.Check
              type="checkbox"
              checked={ prefs.showDuration }
              onChange={(e) => {
                updatePrefs( { showDuration: !prefs.showDuration })
              }
              }
            />
          </div>
        </div>
        {
          config["enable-video-menu"] &&
            <div className="form-section">
              <p className="form-label">Show video menu</p>
              <div className="form-content">
                <Form.Check
                  type="checkbox"
                  checked={ prefs.showMenu }
                  onChange={(e) => { updatePrefs( { showMenu: !prefs.showMenu }) } }
                />
              </div>
            </div>
        }
      </div>

      </>
    </DropDownIcon>
  )
}

export default ConfigMenu