#!/usr/bin/env bash
set -euo pipefail

CONFIG_DIR=".local"
CONFIG_FILE="${CONFIG_DIR}/local-ssh-test.env"

mkdir -p "${CONFIG_DIR}"

if [[ -f "${CONFIG_FILE}" ]]; then
  # shellcheck source=/dev/null
  source "${CONFIG_FILE}"
fi

prompt_with_default() {
  local label="$1"
  local default_value="$2"
  local response
  if [[ -n "${default_value}" ]]; then
    read -r -p "${label} [${default_value}]: " response
    printf '%s' "${response:-${default_value}}"
  else
    read -r -p "${label}: " response
    printf '%s' "${response}"
  fi
}

prompt_secret_keep_existing() {
  local label="$1"
  local has_existing="$2"
  local response
  if [[ "${has_existing}" == "true" ]]; then
    read -r -s -p "${label} (leave empty to keep current): " response
  else
    read -r -s -p "${label}: " response
  fi
  echo >&2
  printf '%s' "${response}"
}

validate_port() {
  local value="$1"
  [[ "${value}" =~ ^[0-9]+$ ]] && (( value >= 1 && value <= 65535 ))
}

ssh_host="$(prompt_with_default "SSH host/IP" "${SSH_HOST:-}")"
ssh_port="$(prompt_with_default "SSH port" "${SSH_PORT:-22}")"
ssh_username="$(prompt_with_default "SSH username" "${SSH_USERNAME:-}")"

if [[ -z "${ssh_host}" || -z "${ssh_username}" ]]; then
  echo "SSH host and username are required."
  exit 1
fi

if ! validate_port "${ssh_port}"; then
  echo "SSH port must be a number between 1 and 65535."
  exit 1
fi

default_auth_choice="1"
if [[ -n "${SSH_PRIVATE_KEY_B64:-}" || -n "${SSH_PRIVATE_KEY:-}" ]]; then
  default_auth_choice="2"
fi

auth_choice=""
while [[ "${auth_choice}" != "1" && "${auth_choice}" != "2" ]]; do
  echo
  echo "Authentication method:"
  echo "  1) Password"
  echo "  2) Private key file"
  auth_choice="$(prompt_with_default "Choose [1/2]" "${default_auth_choice}")"
done

ssh_password=""
ssh_private_key_b64=""

if [[ "${auth_choice}" == "1" ]]; then
  entered_password="$(prompt_secret_keep_existing "SSH password" "$( [[ -n "${SSH_PASSWORD:-}" ]] && printf true || printf false )")"
  entered_password="${entered_password#$'\n'}"
  if [[ -n "${entered_password}" ]]; then
    ssh_password="${entered_password}"
  else
    ssh_password="${SSH_PASSWORD:-}"
  fi
else
  current_key_hint=""
  if [[ -n "${SSH_PRIVATE_KEY_B64:-}" ]]; then
    current_key_hint="(leave empty to keep current key)"
  fi
  while true; do
    key_path="$(prompt_with_default "Path to private key file ${current_key_hint}" "")"
    if [[ -z "${key_path}" && -n "${SSH_PRIVATE_KEY_B64:-}" ]]; then
      ssh_private_key_b64="${SSH_PRIVATE_KEY_B64}"
      break
    fi
    if [[ -f "${key_path}" ]]; then
      ssh_private_key_b64="$(base64 < "${key_path}" | tr -d '\n')"
      break
    fi
    echo "File not found: ${key_path}"
  done
fi

jump_default_enabled="${SSH_JUMP_ENABLED:-false}"
jump_enabled_input="$(prompt_with_default "Use jump server? [y/N]" "$( [[ "${jump_default_enabled}" == "true" ]] && printf y || printf n )")"
case "${jump_enabled_input,,}" in
  y|yes|true|1|on)
    ssh_jump_enabled="true"
    ;;
  *)
    ssh_jump_enabled="false"
    ;;
esac

ssh_jump_host=""
ssh_jump_port="22"
ssh_jump_username=""
ssh_jump_password=""

if [[ "${ssh_jump_enabled}" == "true" ]]; then
  ssh_jump_host="$(prompt_with_default "Jump host/IP" "${SSH_JUMP_HOST:-}")"
  ssh_jump_port="$(prompt_with_default "Jump port" "${SSH_JUMP_PORT:-22}")"
  ssh_jump_username="$(prompt_with_default "Jump username" "${SSH_JUMP_USERNAME:-}")"
  entered_jump_password="$(prompt_secret_keep_existing "Jump password" "$( [[ -n "${SSH_JUMP_PASSWORD:-}" ]] && printf true || printf false )")"
  entered_jump_password="${entered_jump_password#$'\n'}"
  if [[ -n "${entered_jump_password}" ]]; then
    ssh_jump_password="${entered_jump_password}"
  else
    ssh_jump_password="${SSH_JUMP_PASSWORD:-}"
  fi

  if [[ -z "${ssh_jump_host}" || -z "${ssh_jump_username}" ]]; then
    echo "Jump host and jump username are required when jump server is enabled."
    exit 1
  fi

  if ! validate_port "${ssh_jump_port}"; then
    echo "Jump port must be a number between 1 and 65535."
    exit 1
  fi
fi

{
  printf "SSH_HOST=%q\n" "${ssh_host}"
  printf "SSH_PORT=%q\n" "${ssh_port}"
  printf "SSH_USERNAME=%q\n" "${ssh_username}"
  printf "SSH_PASSWORD=%q\n" "${ssh_password}"
  printf "SSH_PRIVATE_KEY_B64=%q\n" "${ssh_private_key_b64}"
  printf "SSH_JUMP_ENABLED=%q\n" "${ssh_jump_enabled}"
  printf "SSH_JUMP_HOST=%q\n" "${ssh_jump_host}"
  printf "SSH_JUMP_PORT=%q\n" "${ssh_jump_port}"
  printf "SSH_JUMP_USERNAME=%q\n" "${ssh_jump_username}"
  printf "SSH_JUMP_PASSWORD=%q\n" "${ssh_jump_password}"
} > "${CONFIG_FILE}"

chmod 600 "${CONFIG_FILE}"

echo
echo "Saved local SSH test configuration to ${CONFIG_FILE}"
echo "This file is git-ignored."
echo
echo "Run tests with: ./scripts/run-local-ssh-test.sh"
