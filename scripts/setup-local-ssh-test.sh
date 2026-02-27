#!/usr/bin/env bash
set -euo pipefail

CONFIG_DIR=".local"
CONFIG_FILE="${CONFIG_DIR}/local-ssh-test.env"

mkdir -p "${CONFIG_DIR}"

read -r -p "SSH host/IP: " ssh_host
read -r -p "SSH port [22]: " ssh_port
read -r -p "SSH username: " ssh_username

ssh_port="${ssh_port:-22}"

if [[ -z "${ssh_host}" || -z "${ssh_username}" ]]; then
  echo "SSH host and username are required."
  exit 1
fi

if ! [[ "${ssh_port}" =~ ^[0-9]+$ ]] || (( ssh_port < 1 || ssh_port > 65535 )); then
  echo "SSH port must be a number between 1 and 65535."
  exit 1
fi

auth_choice=""
while [[ "${auth_choice}" != "1" && "${auth_choice}" != "2" ]]; do
  echo
  echo "Authentication method:"
  echo "  1) Password"
  echo "  2) Private key file"
  read -r -p "Choose [1/2]: " auth_choice
done

ssh_password=""
ssh_private_key_b64=""

if [[ "${auth_choice}" == "1" ]]; then
  read -r -s -p "SSH password: " ssh_password
  echo
else
  while true; do
    read -r -p "Path to private key file: " key_path
    if [[ -f "${key_path}" ]]; then
      break
    fi
    echo "File not found: ${key_path}"
  done
  ssh_private_key_b64="$(base64 < "${key_path}" | tr -d '\n')"
fi

{
  printf "SSH_HOST=%q\n" "${ssh_host}"
  printf "SSH_PORT=%q\n" "${ssh_port}"
  printf "SSH_USERNAME=%q\n" "${ssh_username}"
  printf "SSH_PASSWORD=%q\n" "${ssh_password}"
  printf "SSH_PRIVATE_KEY_B64=%q\n" "${ssh_private_key_b64}"
} > "${CONFIG_FILE}"

chmod 600 "${CONFIG_FILE}"

echo
echo "Saved local SSH test configuration to ${CONFIG_FILE}"
echo "This file is git-ignored."
echo
echo "Run tests with: ./scripts/run-local-ssh-test.sh"
